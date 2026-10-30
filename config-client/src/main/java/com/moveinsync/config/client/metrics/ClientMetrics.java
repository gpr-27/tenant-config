package com.moveinsync.config.client.metrics;

import java.time.Duration;

// the embedding app adapts this to its metrics library (the demo uses Micrometer), so the client stays dependency-free
public interface ClientMetrics {

    void version(long version);

    // server version minus local version, seen by the heal poll
    void lag(long versions);

    // now minus the change's createdAt when a pushed change is applied
    void eventAge(Duration age);

    void staleEvent();

    void gapResync();

    void reconnect();

    void readTimer(long nanos);
}
