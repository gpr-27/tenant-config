package com.moveinsync.config.loadtest;

import com.moveinsync.config.client.metrics.ClientMetrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// counts what the load tests assert on; "since server answered" is how stale the client may be, which only grows while the server is gone
final class CountingClientMetrics implements ClientMetrics {

    private final AtomicInteger reconnects = new AtomicInteger();
    private final AtomicLong lagVersions = new AtomicLong();
    private volatile long serverAnsweredAt = System.nanoTime();

    int reconnects() {
        return reconnects.get();
    }

    long lagVersions() {
        return lagVersions.get();
    }

    Duration sinceServerAnswered() {
        return Duration.ofNanos(System.nanoTime() - serverAnsweredAt);
    }

    @Override
    public void version(long version) {
        serverAnsweredAt = System.nanoTime();
    }

    @Override
    public void lag(long versions) {
        lagVersions.set(versions);
        serverAnsweredAt = System.nanoTime();
    }

    @Override
    public void eventAge(Duration age) {
        serverAnsweredAt = System.nanoTime();
    }

    @Override
    public void alive() {
        serverAnsweredAt = System.nanoTime();
    }

    @Override
    public void staleEvent() {
    }

    @Override
    public void gapResync() {
    }

    @Override
    public void reconnect() {
        reconnects.incrementAndGet();
    }

    @Override
    public void readTimer(long nanos) {
    }
}
