package com.moveinsync.config.loadtest;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.store.NoopSnapshotStore;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.ConfigServerApplication;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import com.moveinsync.config.server.state.StateLoader;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

// 500 clients lose their streams to a server restart, two changes land meanwhile, and all of them catch up
// by Last-Event-ID replay from the RecentChanges ring the restarted server rebuilt from the database. The scenario runs once; each test checks one thing it promised.
@SpringBootTest(classes = ConfigServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconnectStormTest {

    private static final int CLIENTS = 500;
    // a fixed port instead of RANDOM_PORT: Tomcat restarted on port 0 would bind a new port the clients don't know
    private static final int PORT = freePort();

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    @Autowired
    ServletWebServerApplicationContext context;
    @Autowired
    ChangeSetService changeSets;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    CurrentState currentState;
    @Autowired
    RecentChanges recentChanges;
    @Autowired
    StateLoader stateLoader;

    private final List<ConfigClient> clients = new ArrayList<>();
    private final List<CountingClientMetrics> metrics = new ArrayList<>();
    // reconnects and heal polls are short tasks: four threads serve all 500 clients
    private final ScheduledExecutorService exec = Executors.newScheduledThreadPool(4);

    private Duration timeToAllCurrent;
    private long statementsDuringCatchUp;
    private long queriesDuringCatchUp;

    @BeforeAll
    void restartTheServerUnderFiveHundredClients(@TempDir Path dir) {
        ConfigServerApi api = new ConfigServerApi("http://localhost:" + PORT);
        String serviceToken = api.login("booking-svc", "svc");
        String operatorToken = api.login("acme-ops", "ops");
        for (int i = 0; i < CLIENTS; i++) {
            ConfigClientOptions options = ConfigClientOptions.defaults(URI.create("http://localhost:" + PORT),
                    () -> serviceToken, dir.resolve("client-" + i + ".json"));
            CountingClientMetrics counting = new CountingClientMetrics();
            ConfigClient client = new ConfigClient(options, new HttpConfigTransport(options), new NoopSnapshotStore(),
                    counting, exec);
            client.start();
            clients.add(client);
            metrics.add(counting);
        }
        long published = 0;
        for (int i = 0; i < 3; i++) {
            published = api.publish(operatorToken, "acme", "blr-ecospace", "booking.cutoff", "PT" + (30 + i) + "M");
        }
        awaitAllClientsAt(published);

        WebServer webServer = context.getWebServer();
        webServer.stop();
        // committed while every stream is down, so the reconnect has something to replay rather than an empty catch-up
        submit("PT40M");
        long target = submit("PT41M");
        // a restarted process starts with empty memory and reloads values, version and ring from the database
        currentState.replace(Snapshot.empty());
        recentChanges.replaceAll(List.of());
        stateLoader.load();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long statementsBefore = statistics.getPrepareStatementCount();
        long queriesBefore = statistics.getQueryExecutionCount();
        long restartedAt = System.nanoTime();
        webServer.start();
        awaitAllClientsAt(target);
        timeToAllCurrent = Duration.ofNanos(System.nanoTime() - restartedAt);
        statementsDuringCatchUp = statistics.getPrepareStatementCount() - statementsBefore;
        queriesDuringCatchUp = statistics.getQueryExecutionCount() - queriesBefore;

        System.out.printf("reconnect storm: %d clients current %d ms after restart; %d SQL statements, %d queries%n",
                CLIENTS, timeToAllCurrent.toMillis(), statementsDuringCatchUp, queriesDuringCatchUp);
    }

    @AfterAll
    void closeClients() {
        for (ConfigClient client : clients) {
            client.close();
        }
        exec.shutdownNow();
    }

    @Test
    void allFiveHundredClientsAreCurrentWithinTenSecondsOfTheRestart() {
        assertThat(timeToAllCurrent).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void catchUpRunsNoDatabaseStatementSoNoChangeLogRowIsSelected() {
        assertThat(statementsDuringCatchUp).isZero();
        assertThat(queriesDuringCatchUp).isZero();
    }

    @Test
    void everyClientLostItsStreamAndReconnected() {
        for (CountingClientMetrics counting : metrics) {
            assertThat(counting.reconnects()).isPositive();
        }
    }

    private long submit(String cutoff) {
        Operation set = new Operation(OpKind.SET, ScopeKey.office("blr-ecospace"), "booking.cutoff",
                TextNode.valueOf(cutoff));
        return changeSets.submit(new ChangeSet("acme", "acme-ops", "reconnect storm", List.of(set)),
                ChangeKind.CHANGE, null);
    }

    // generous on purpose: the 10 s promise is asserted on the measured time, so a miss reports the real number
    private void awaitAllClientsAt(long version) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(20)).until(() -> {
            for (ConfigClient client : clients) {
                if (client.version() != version) {
                    return false;
                }
            }
            return true;
        });
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
