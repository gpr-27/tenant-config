package com.moveinsync.config.server.push;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.security.JwtService;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// a ring of 5 makes "older than the ring" reachable: the 8 seeded definitions already push version 1 out of it
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "config.recent.capacity=5")
@Import(TestDatabase.class)
class PushEndToEndIT {

    @LocalServerPort
    int port;
    @Autowired
    JwtService jwt;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    final HttpClient http = HttpClient.newHttpClient();
    final List<ConfigClient> clients = new ArrayList<>();

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(ConfigClient::close);
    }

    @Test
    void changeIsVisibleInARealClientWithinFiveSeconds(@TempDir Path dir) throws Exception {
        ConfigClient client = startClient(dir);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.version() == 8);

        long version = postCutoff("PT30M");

        await().atMost(Duration.ofSeconds(5)).until(() -> client.version() == version);
        ScopePath bengaluru = new ScopePath("acme", "blr-ecospace", null);
        assertThat(client.get("acme", "booking.cutoff", bengaluru, Duration.class)).isEqualTo(Duration.ofMinutes(30));
        assertThat(client.get("acme", "booking.cutoff", new ScopePath("acme", "hyd-hitec", null), Duration.class))
                .isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void reconnectWithLastEventIdReplaysWhatWasMissed() throws Exception {
        long first = postCutoff("PT30M");
        long second = postCutoff("PT40M");

        List<String> lines = firstFrames(first - 1, 2);

        assertThat(lines).containsSubsequence("id: " + first, "event: change", "id: " + second, "event: change");
        assertThat(lines).anyMatch(line -> line.startsWith("data: {") && line.contains("\"version\":" + second));
    }

    @Test
    void clientOlderThanTheRingIsToldToResyncAndGetsAFullSnapshot(@TempDir Path dir) throws Exception {
        assertThat(firstFrames(1, 1)).containsExactly("data: {\"type\":\"resync\"}");

        String snapshot = get("/api/v1/config/snapshot?since=1");
        assertThat(snapshot).startsWith("{\"type\":\"full\"").contains("\"definitions\"");

        ConfigClient client = startClient(dir);
        await().atMost(Duration.ofSeconds(5)).until(() -> client.version() == 8);
    }

    private ConfigClient startClient(Path dir) {
        ConfigClientOptions options = ConfigClientOptions.defaults(URI.create("http://localhost:" + port),
                () -> jwt.issue("booking-svc", Role.SERVICE, null), dir.resolve("snapshot.json"));
        ConfigClient client = new ConfigClient(options);
        clients.add(client);
        client.start();
        return client;
    }

    private long postCutoff(String value) throws Exception {
        String body = """
                {"reason": "pilot", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "%s"}]}
                """.formatted(value);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/tenants/acme/changes"))
                .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.parseLong(response.body().replaceAll("\\D", ""));
    }

    private String get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + jwt.issue("booking-svc", Role.SERVICE, null))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    // reads the raw stream until `frames` blank-line-terminated frames have arrived, then hangs up
    private List<String> firstFrames(long lastEventId, int frames) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/config/stream"))
                .header("Authorization", "Bearer " + jwt.issue("booking-svc", Role.SERVICE, null))
                .header("Accept", "text/event-stream")
                .header("Last-Event-ID", String.valueOf(lastEventId))
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            int seen = 0;
            while (seen < frames) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (line.isEmpty()) {
                    seen++;
                } else if (!line.startsWith(":")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }
}
