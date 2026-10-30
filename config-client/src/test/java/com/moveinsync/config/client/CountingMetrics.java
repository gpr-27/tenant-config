package com.moveinsync.config.client;

import com.moveinsync.config.client.metrics.ClientMetrics;

import java.time.Duration;

final class CountingMetrics implements ClientMetrics {

    long version;
    long lag;
    int staleEvents;
    int gapResyncs;
    int reconnects;

    @Override
    public void version(long version) {
        this.version = version;
    }

    @Override
    public void lag(long versions) {
        this.lag = versions;
    }

    @Override
    public void eventAge(Duration age) {
    }

    @Override
    public void staleEvent() {
        staleEvents++;
    }

    @Override
    public void gapResync() {
        gapResyncs++;
    }

    @Override
    public void reconnect() {
        reconnects++;
    }

    @Override
    public void readTimer(long nanos) {
    }
}
