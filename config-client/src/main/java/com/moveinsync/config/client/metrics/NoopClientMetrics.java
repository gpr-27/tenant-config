package com.moveinsync.config.client.metrics;

import java.time.Duration;

public final class NoopClientMetrics implements ClientMetrics {

    @Override
    public void version(long version) {
    }

    @Override
    public void lag(long versions) {
    }

    @Override
    public void eventAge(Duration age) {
    }

    @Override
    public void staleEvent() {
    }

    @Override
    public void gapResync() {
    }

    @Override
    public void reconnect() {
    }

    @Override
    public void readTimer(long nanos) {
    }
}
