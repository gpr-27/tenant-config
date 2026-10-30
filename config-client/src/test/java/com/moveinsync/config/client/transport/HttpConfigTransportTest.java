package com.moveinsync.config.client.transport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.Fixtures;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// the wire format against a real socket: the JDK's own HttpServer plays config-server
class HttpConfigTransportTest {

    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final List<String> lastEventIds = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private HttpConfigTransport transport;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config/version", exchange -> {
            // the first token handed out has "expired"
            if (exchange.getRequestHeaders().getFirst("Authorization").equals("Bearer token-1")) {
                respond(exchange, 401, "");
            } else {
                respond(exchange, 200, "{\"version\": 144}");
            }
        });
        server.createContext("/api/v1/config/snapshot", exchange -> {
            if (exchange.getRequestURI().getQuery().equals("since=43")) {
                ObjectNode delta = SnapshotCodec.mapper().createObjectNode().put("type", "delta").put("from", 43).put("to", 45);
                delta.putArray("changes").add(ChangeEventCodec.toJson(Fixtures.cutoff(44, "PT30M")))
                        .add(ChangeEventCodec.toJson(Fixtures.cutoff(45, "PT20M")));
                respond(exchange, 200, delta.toString());
            } else {
                ObjectNode full = SnapshotCodec.toJson(Fixtures.snapshotAt(144)).put("type", "full");
                respond(exchange, 200, full.toString());
            }
        });
        server.createContext("/api/v1/config/stream", exchange -> {
            lastEventIds.add(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write((": ping\n\n"
                        + "id: 44\nevent: change\ndata: " + ChangeEventCodec.toJson(Fixtures.cutoff(44, "PT30M")) + "\n\n"
                        + "data: {\"type\":\"resync\"}\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        transport = new HttpConfigTransport(ConfigClientOptions.defaults(base,
                () -> "token-" + tokensIssued.incrementAndGet(), Path.of("unused.json")));
    }

    @AfterEach
    void stopServer() {
        transport.close();
        server.stop(0);
    }

    @Test
    void unauthorizedAsksForANewTokenOnceAndRetries() {
        assertThat(transport.version()).isEqualTo(144);
        assertThat(tokensIssued).hasValue(2);
    }

    @Test
    void deltaAndFullAnswersAreDecoded() {
        SnapshotResponse delta = transport.snapshotSince(43);
        SnapshotResponse full = transport.snapshotSince(0);

        assertThat(delta).isInstanceOfSatisfying(SnapshotResponse.Delta.class, d ->
                assertThat(d.changes()).extracting(ChangeEvent::version).containsExactly(44L, 45L));
        assertThat(full).isInstanceOfSatisfying(SnapshotResponse.Full.class, f ->
                assertThat(f.snapshot().version()).isEqualTo(144));
    }

    @Test
    void streamDeliversFramesThenReportsTheClose() throws InterruptedException {
        List<ChangeEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger resyncs = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);

        transport.openStream(43, events::add, resyncs::incrementAndGet, closed::countDown);

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(lastEventIds).containsExactly("43");
        assertThat(events).extracting(ChangeEvent::version).containsExactly(44L);
        assertThat(resyncs).hasValue(1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
