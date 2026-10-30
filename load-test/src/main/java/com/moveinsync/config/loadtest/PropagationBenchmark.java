package com.moveinsync.config.loadtest;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.metrics.NoopClientMetrics;
import com.moveinsync.config.client.store.NoopSnapshotStore;
import com.moveinsync.config.client.transport.HttpConfigTransport;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

// 200 real clients against a running server (docker compose up); publish 20 changes one at a time and time
// each one from "POST returned" to "this client's listener saw the version"
public final class PropagationBenchmark {

    private static final int CLIENTS = 200;
    private static final int PUBLISHES = 20;

    public static void main(String[] args) throws Exception {
        String base = System.getenv().getOrDefault("BASE", "http://localhost:8080");
        ConfigServerApi api = new ConfigServerApi(base);
        String serviceToken = api.login("booking-svc", "svc");
        String operatorToken = api.login("acme-ops", "ops");

        // the clients only schedule reconnects and heal polls on it; the HTTP work runs on each HttpClient
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(4, Thread.ofPlatform().daemon().factory());
        Path dir = Files.createTempDirectory("propagation");
        Arrivals arrivals = new Arrivals(CLIENTS);
        List<ConfigClient> clients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            ConfigClientOptions options = ConfigClientOptions.defaults(URI.create(base), () -> serviceToken,
                    dir.resolve("client-" + i + ".json"));
            ConfigClient client = new ConfigClient(options, new HttpConfigTransport(options), new NoopSnapshotStore(),
                    new NoopClientMetrics(), exec);
            int index = i;
            client.addListener(snapshot -> arrivals.record(index, snapshot.version()));
            client.start();
            clients.add(client);
        }
        awaitClientsAt(clients, api.version(serviceToken));

        List<double[]> perPublish = new ArrayList<>();
        List<Long> versions = new ArrayList<>();
        for (int p = 0; p < PUBLISHES; p++) {
            long version = api.publish(operatorToken, "acme", "blr-ecospace", "booking.cutoff", "PT" + (30 + p) + "M");
            long returnedAt = System.nanoTime();
            arrivals.await(version, Duration.ofSeconds(10));
            perPublish.add(arrivals.millisSince(version, returnedAt));
            versions.add(version);
        }

        for (ConfigClient client : clients) {
            client.close();
        }
        exec.shutdownNow();
        System.out.println(table(versions, perPublish));
    }

    private static void awaitClientsAt(List<ConfigClient> clients, long version) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        for (ConfigClient client : clients) {
            while (client.version() < version) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("clients did not reach server version " + version + " in 60 s");
                }
                Thread.sleep(10);
            }
        }
    }

    private static String table(List<Long> versions, List<double[]> perPublish) {
        StringBuilder out = new StringBuilder();
        out.append("| publish | version | p50 ms | p99 ms | max ms |\n");
        out.append("|---:|---:|---:|---:|---:|\n");
        List<Double> all = new ArrayList<>();
        for (int p = 0; p < perPublish.size(); p++) {
            double[] millis = perPublish.get(p);
            out.append(row(String.valueOf(p + 1), String.valueOf(versions.get(p)), millis));
            for (double m : millis) {
                all.add(m);
            }
        }
        double[] overall = new double[all.size()];
        for (int i = 0; i < overall.length; i++) {
            overall[i] = all.get(i);
        }
        out.append(row("all", "", overall));
        return out.toString();
    }

    private static String row(String label, String version, double[] millis) {
        double[] sorted = millis.clone();
        Arrays.sort(sorted);
        return String.format("| %s | %s | %.2f | %.2f | %.2f |%n", label, version,
                percentile(sorted, 0.50), percentile(sorted, 0.99), sorted[sorted.length - 1]);
    }

    // nearest rank: with 200 samples p99 is the 198th value, so one slow client does not hide in an average
    static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }
}
