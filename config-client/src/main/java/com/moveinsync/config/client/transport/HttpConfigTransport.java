package com.moveinsync.config.client.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.snapshot.SnapshotCodec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class HttpConfigTransport implements ConfigTransport {

    private static final System.Logger log = System.getLogger(HttpConfigTransport.class.getName());

    private final String base;
    private final Supplier<String> tokenSupplier;
    private final Duration readTimeout;
    private final HttpClient http;

    // null until first needed and after a 401, so the next request asks the supplier for a fresh one
    private volatile String token;
    private volatile SseSubscriber stream;

    public HttpConfigTransport(ConfigClientOptions options) {
        String uri = options.serverBaseUri().toString();
        this.base = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        this.tokenSupplier = options.tokenSupplier();
        this.readTimeout = options.readTimeout();
        this.http = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                // plain HTTP/1.1: no h2c upgrade in front of a long-lived event stream
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public long version() {
        return getJson("/api/v1/config/version").path("version").asLong();
    }

    @Override
    public SnapshotResponse snapshotSince(long version) {
        JsonNode json = getJson("/api/v1/config/snapshot?since=" + version);
        String type = json.path("type").asText();
        switch (type) {
            case "delta" -> {
                List<ChangeEvent> changes = new ArrayList<>();
                for (JsonNode change : json.path("changes")) {
                    changes.add(ChangeEventCodec.fromJson(change));
                }
                return new SnapshotResponse.Delta(json.path("from").asLong(), json.path("to").asLong(), changes);
            }
            case "full" -> {
                return new SnapshotResponse.Full(SnapshotCodec.fromJson(json));
            }
            default -> throw new UncheckedIOException(new IOException("unknown snapshot response type '" + type + "'"));
        }
    }

    @Override
    public void openStream(long lastEventId, Consumer<ChangeEvent> onEvent, Runnable onResync, Runnable onClose) {
        SseSubscriber subscriber = new SseSubscriber(onEvent, onResync, onClose, readTimeout);
        stream = subscriber;
        HttpRequest request;
        try {
            request = request("/api/v1/config/stream")
                    .header("Accept", "text/event-stream")
                    .header("Last-Event-ID", Long.toString(lastEventId))
                    .build();
        } catch (RuntimeException e) {
            // the token supplier usually logs in over HTTP, so it fails while the server is down: same as a refused connection
            log.log(Level.INFO, "cannot open push stream: " + e);
            subscriber.close();
            return;
        }

        http.sendAsync(request, info -> {
            if (info.statusCode() != 200) {
                return BodySubscribers.replacing(null);
            }
            log.log(Level.INFO, "push stream connected to " + base + ", Last-Event-ID " + lastEventId);
            return BodySubscribers.fromLineSubscriber(subscriber);
        }).whenComplete((response, error) -> {
            if (error != null) {
                log.log(Level.INFO, "push stream failed: " + error);
            } else if (response.statusCode() == 401) {
                token = null;
            } else if (response.statusCode() != 200) {
                log.log(Level.INFO, "push stream refused with HTTP " + response.statusCode());
            }
            // completes only once the body has ended, so this is the stream closing
            subscriber.close();
        });
    }

    @Override
    public void close() {
        SseSubscriber current = stream;
        if (current != null) {
            current.cancel();
        }
        http.shutdownNow();
    }

    private JsonNode getJson(String path) {
        HttpResponse<String> response = send(path);
        if (response.statusCode() == 401) {
            token = null;
            response = send(path);
        }
        if (response.statusCode() != 200) {
            throw new UncheckedIOException(new IOException("GET " + path + " answered HTTP " + response.statusCode()));
        }
        try {
            return SnapshotCodec.mapper().readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HttpResponse<String> send(String path) {
        try {
            return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("interrupted during GET " + path));
        }
    }

    private HttpRequest.Builder request(String path) {
        String current = token;
        if (current == null) {
            current = tokenSupplier.get();
            token = current;
        }
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + current)
                .GET();
    }
}
