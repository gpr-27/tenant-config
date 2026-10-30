package com.moveinsync.config.loadtest;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.store.NoopSnapshotStore;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.core.scope.ScopePath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// the real server image is killed under 5 clients: reads keep answering from memory, and the clients
// catch up on their own once a new container is back on the same address
class ServerKillIT {

    private static final int CLIENTS = 5;
    private static final ScopePath BLR = new ScopePath("acme", "blr-ecospace", null);

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("config")
            .withUsername("config")
            .withPassword("config");
    private static final int PORT = freePort();
    private static final GenericContainer<?> SERVER = new GenericContainer<>(serverImage())
            .withNetwork(NETWORK)
            .withEnv("CONFIG_DB_URL", "jdbc:postgresql://postgres:5432/config")
            .withEnv("CONFIG_DB_USER", "config")
            .withEnv("CONFIG_DB_PASSWORD", "config")
            .withEnv("CONFIG_JWT_SECRET", "server-kill-it-secret-0123456789abcdef0123456789")
            .withExposedPorts(8080)
            // stop() removes the container and start() makes a new one, which would get a new random host port;
            // a fixed binding brings it back where the clients are already pointed, as a restarted host would
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(PORT), new ExposedPort(8080))))
            // StateLoader fills memory before Tomcat opens the port, so a healthy answer means state is loaded too
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8080).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static final List<ConfigClient> clients = new ArrayList<>();
    private static final List<CountingClientMetrics> metrics = new ArrayList<>();
    private static final ScheduledExecutorService exec = Executors.newScheduledThreadPool(2);

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        SERVER.start();
    }

    @AfterAll
    static void stopEverything() {
        for (ConfigClient client : clients) {
            client.close();
        }
        exec.shutdownNow();
        SERVER.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @Test
    void readsKeepServingTheLastVersionWhileTheServerIsDownAndClientsCatchUpWithinFiveSecondsOfItsReturn(
            @TempDir Path dir) throws InterruptedException {
        String base = "http://" + SERVER.getHost() + ":" + PORT;
        ConfigServerApi api = new ConfigServerApi(base);
        String serviceToken = api.login("booking-svc", "svc");
        String operatorToken = api.login("acme-ops", "ops");
        for (int i = 0; i < CLIENTS; i++) {
            // a 1 s heal poll so "time since the server last answered" is fresh while it is up; the default is 30 s
            ConfigClientOptions defaults = ConfigClientOptions.defaults(URI.create(base), () -> serviceToken,
                    dir.resolve("client-" + i + ".json"));
            ConfigClientOptions options = new ConfigClientOptions(defaults.serverBaseUri(), defaults.tokenSupplier(),
                    defaults.snapshotFile(), Duration.ofSeconds(1), defaults.backoffBase(), defaults.backoffCap(),
                    defaults.connectTimeout(), defaults.readTimeout());
            CountingClientMetrics counting = new CountingClientMetrics();
            ConfigClient client = new ConfigClient(options, new HttpConfigTransport(options), new NoopSnapshotStore(),
                    counting, exec);
            client.start();
            clients.add(client);
            metrics.add(counting);
        }

        long v = api.publish(operatorToken, "acme", "blr-ecospace", "booking.cutoff", "PT45M");
        awaitAllAt(v, Duration.ofSeconds(5));
        List<Integer> reconnectsBefore = new ArrayList<>();
        for (CountingClientMetrics counting : metrics) {
            reconnectsBefore.add(counting.reconnects());
        }

        SERVER.stop();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (ConfigClient client : clients) {
                assertThat(client.version()).isEqualTo(v);
                assertThat(client.get("acme", "booking.cutoff", BLR, Duration.class)).isEqualTo(Duration.ofMinutes(45));
            }
            Thread.sleep(250);
        }
        for (int i = 0; i < CLIENTS; i++) {
            assertThat(metrics.get(i).sinceServerAnswered()).isGreaterThanOrEqualTo(Duration.ofSeconds(10));
            assertThat(metrics.get(i).reconnects()).isGreaterThan(reconnectsBefore.get(i));
        }

        SERVER.start();
        // same JWT secret in the new container, so the tokens from before the kill are still good
        long next = api.publish(operatorToken, "acme", "blr-ecospace", "booking.cutoff", "PT50M");
        assertThat(next).isEqualTo(v + 1);
        awaitAllAt(next, Duration.ofSeconds(5));
        for (ConfigClient client : clients) {
            assertThat(client.get("acme", "booking.cutoff", BLR, Duration.class)).isEqualTo(Duration.ofMinutes(50));
        }
    }

    private static void awaitAllAt(long version, Duration within) {
        await().atMost(within).pollInterval(Duration.ofMillis(50)).until(() -> {
            for (ConfigClient client : clients) {
                if (client.version() != version) {
                    return false;
                }
            }
            return true;
        });
    }

    // only what config-server/Dockerfile copies, so no local target/ directory or IDE files enter the build context
    private static ImageFromDockerfile serverImage() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        ImageFromDockerfile image = new ImageFromDockerfile("tenant-config-config-server-it", false)
                .withDockerfilePath("config-server/Dockerfile");
        for (String path : List.of("pom.xml", "config-core/pom.xml", "config-core/src", "config-client/pom.xml",
                "config-server/pom.xml", "config-server/src", "config-server/Dockerfile",
                "booking-service-demo/pom.xml", "load-test/pom.xml")) {
            image.withFileFromPath(path, root.resolve(path));
        }
        return image;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
