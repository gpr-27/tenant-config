package com.moveinsync.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.store.FileSnapshotStore;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ConfigClientConfig {

    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("config-client").daemon().factory());

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Bean(destroyMethod = "close")
    public ConfigClient configClient(@Value("${config.server-url}") URI serverUrl,
                                     @Value("${config.username}") String username,
                                     @Value("${config.password}") String password,
                                     @Value("${config.snapshot-file}") Path snapshotFile,
                                     MeterRegistry registry,
                                     ConfigUpdateLogger updateLogger) {
        // no caching here: the transport keeps the token and only calls us again on first use or after a 401
        ConfigClientOptions options = ConfigClientOptions.defaults(serverUrl,
                () -> login(serverUrl, username, password), snapshotFile);
        ConfigClient client = new ConfigClient(options, new HttpConfigTransport(options),
                new FileSnapshotStore(snapshotFile), new MicrometerClientMetrics(registry), exec);
        client.addListener(updateLogger);
        // a down server does not fail start-up: the client serves the disk copy and keeps retrying in the background
        client.start();
        return client;
    }

    // the client bean depends on this configuration, so it is closed before the scheduler goes away
    @PreDestroy
    void shutdown() {
        exec.shutdownNow();
    }

    private String login(URI serverUrl, String username, String password) {
        ObjectMapper mapper = SnapshotCodec.mapper();
        try {
            String body = mapper.writeValueAsString(Map.of("username", username, "password", password));
            HttpRequest request = HttpRequest.newBuilder(serverUrl.resolve("/api/v1/auth/token"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("login as " + username + " answered HTTP " + response.statusCode());
            }
            JsonNode json = mapper.readTree(response.body());
            return json.path("token").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("interrupted during login"));
        }
    }
}
