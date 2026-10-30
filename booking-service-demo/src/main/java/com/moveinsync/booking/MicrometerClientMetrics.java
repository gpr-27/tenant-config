package com.moveinsync.booking;

import com.moveinsync.config.client.metrics.ClientMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

// counters are registered without "_total" and the timer without "_seconds": the Prometheus registry appends them
public class MicrometerClientMetrics implements ClientMetrics {

    private final AtomicLong version = new AtomicLong();
    private final AtomicLong lagVersions = new AtomicLong();
    private final LongSupplier clock;
    // when we last knew we were current: a stream heartbeat (every 15 s), an applied event, or a heal poll with no lag.
    // so the gauge stays under ~15 s while connected and grows only while the server is unreachable
    private final AtomicLong lastCurrentMillis;
    private final Counter reconnects;
    private final Counter staleEvents;
    private final Counter gapResyncs;
    private final Timer readLatency;

    public MicrometerClientMetrics(MeterRegistry registry) {
        this(registry, System::currentTimeMillis);
    }

    MicrometerClientMetrics(MeterRegistry registry, LongSupplier clock) {
        this.clock = clock;
        this.lastCurrentMillis = new AtomicLong(clock.getAsLong());
        Gauge.builder("config_client_version", version, AtomicLong::get).register(registry);
        Gauge.builder("config_client_lag_versions", lagVersions, AtomicLong::get).register(registry);
        Gauge.builder("config_client_lag_seconds", lastCurrentMillis,
                since -> (clock.getAsLong() - since.get()) / 1000.0).register(registry);
        reconnects = Counter.builder("config_client_reconnects").register(registry);
        staleEvents = Counter.builder("config_client_stale_events").register(registry);
        gapResyncs = Counter.builder("config_client_gap_resync").register(registry);
        readLatency = Timer.builder("config_client_read_latency").register(registry);
    }

    @Override
    public void version(long version) {
        this.version.set(version);
    }

    @Override
    public void lag(long versions) {
        lagVersions.set(versions);
        if (versions == 0) {
            lastCurrentMillis.set(clock.getAsLong());
        }
    }

    @Override
    public void eventAge(Duration age) {
        lastCurrentMillis.set(clock.getAsLong() - age.toMillis());
    }

    @Override
    public void alive() {
        lastCurrentMillis.set(clock.getAsLong());
    }

    @Override
    public void staleEvent() {
        staleEvents.increment();
    }

    @Override
    public void gapResync() {
        gapResyncs.increment();
    }

    @Override
    public void reconnect() {
        reconnects.increment();
    }

    @Override
    public void readTimer(long nanos) {
        readLatency.record(nanos, TimeUnit.NANOSECONDS);
    }
}
