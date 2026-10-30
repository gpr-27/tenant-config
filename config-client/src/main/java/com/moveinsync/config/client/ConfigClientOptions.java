package com.moveinsync.config.client;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

public record ConfigClientOptions(URI serverBaseUri, Supplier<String> tokenSupplier, Path snapshotFile,
                                  Duration healPollInterval, Duration backoffBase, Duration backoffCap,
                                  Duration connectTimeout, Duration readTimeout) {

    public static ConfigClientOptions defaults(URI base, Supplier<String> tokenSupplier, Path file) {
        return new ConfigClientOptions(base, tokenSupplier, file,
                Duration.ofSeconds(30),     // heal poll: bounds how long a lost push can go unnoticed; ~17 req/s for 500 clients
                Duration.ofMillis(500),     // backoff base: first reconnect waits 0-0.5 s
                Duration.ofSeconds(10),     // backoff cap: a restarted server is back within seconds, so never wait longer
                Duration.ofSeconds(5),      // connect timeout: a reachable server answers a TCP connect in well under 5 s
                Duration.ofSeconds(60));    // read timeout: the server sends a heartbeat every 15 s, so 60 s of silence is a dead stream
    }
}
