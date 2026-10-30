package com.moveinsync.config.client.metrics;

import java.time.Duration;

// the embedding app adapts this to its metrics library (the demo uses Micrometer), so the client stays dependency-free
public interface ClientMetrics {

    void version(long version);

    // server version minus local version, seen by the heal poll
    void lag(long versions);

    // now minus the change's createdAt when a pushed change is applied
    void eventAge(Duration age);

    // the push stream sent a heartbeat or a frame: it is open, so any change would have reached us by now
    void alive();

    void staleEvent();

    void gapResync();

    void reconnect();

    void readTimer(long nanos);
}
