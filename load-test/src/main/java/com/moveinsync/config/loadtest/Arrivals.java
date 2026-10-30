package com.moveinsync.config.loadtest;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

// when each client's listener first saw each version, in System.nanoTime(); 0 means not yet
final class Arrivals {

    private final int clients;
    private final Map<Long, AtomicLongArray> byVersion = new ConcurrentHashMap<>();
    // written only from client i's listener, which ConfigClient calls under its own lock, one call at a time
    private final long[] lastSeen;

    Arrivals(int clients) {
        this.clients = clients;
        this.lastSeen = new long[clients];
    }

    void record(int client, long version) {
        long now = System.nanoTime();
        long previous = lastSeen[client];
        lastSeen[client] = version;
        // the first snapshot is the initial load, not a propagation
        if (previous == 0) {
            return;
        }
        // a resync can jump several versions at once; each of them arrived now
        for (long v = previous + 1; v <= version; v++) {
            byVersion.computeIfAbsent(v, x -> new AtomicLongArray(clients)).compareAndSet(client, 0, now);
        }
    }

    void await(long version, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!complete(version)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("not every client saw version " + version + " within " + timeout);
            }
            Thread.sleep(1);
        }
    }

    // can be negative: a push may reach a client before the operator's POST response does
    double[] millisSince(long version, long startNanos) {
        AtomicLongArray times = byVersion.get(version);
        double[] millis = new double[clients];
        for (int i = 0; i < clients; i++) {
            millis[i] = (times.get(i) - startNanos) / 1_000_000.0;
        }
        return millis;
    }

    private boolean complete(long version) {
        AtomicLongArray times = byVersion.get(version);
        if (times == null) {
            return false;
        }
        for (int i = 0; i < clients; i++) {
            if (times.get(i) == 0) {
                return false;
            }
        }
        return true;
    }
}
