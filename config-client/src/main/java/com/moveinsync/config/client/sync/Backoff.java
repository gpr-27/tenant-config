package com.moveinsync.config.client.sync;

import java.time.Duration;
import java.util.Random;

// full jitter: random(0, min(cap, base * 2^attempt)), so clients reconnecting after a restart spread out instead of arriving together
public final class Backoff {

    private final long baseMillis;
    private final long capMillis;
    private final Random random;
    private int attempt;

    public Backoff(Duration base, Duration cap) {
        this(base, cap, new Random());
    }

    public Backoff(Duration base, Duration cap, Random random) {
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.random = random;
    }

    public synchronized Duration nextDelay() {
        // past 2^30 the cap has long won; stopping there keeps the shift from overflowing
        long ceiling = Math.min(capMillis, baseMillis << Math.min(attempt, 30));
        attempt++;
        return Duration.ofMillis(random.nextLong(0, ceiling + 1));
    }

    public synchronized void reset() {
        attempt = 0;
    }
}
