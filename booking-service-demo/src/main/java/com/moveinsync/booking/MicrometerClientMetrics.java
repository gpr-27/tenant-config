package com.moveinsync.booking;

import com.moveinsync.config.client.metrics.ClientMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// counters are registered without "_total" and the timer without "_seconds": the Prometheus registry appends them
public class MicrometerClientMetrics implements ClientMetrics {

    private final AtomicLong version = new AtomicLong();
    private final AtomicLong lagVersions = new AtomicLong();
    // when we last knew we were current: an applied event, or a heal poll that found no lag.
    // the gauge measures from here, so it keeps growing while the server is unreachable
    private final AtomicLong lastCurrentMillis = new AtomicLong(System.currentTimeMillis());
    private final Counter reconnects;
    private final Counter staleEvents;
    private final Counter gapResyncs;
    private final Timer readLatency;

    public MicrometerClientMetrics(MeterRegistry registry) {
        Gauge.builder("config_client_version", version, AtomicLong::get).register(registry);
        Gauge.builder("config_client_lag_versions", lagVersions, AtomicLong::get).register(registry);
        Gauge.builder("config_client_lag_seconds", lastCurrentMillis,
                since -> (System.currentTimeMillis() - since.get()) / 1000.0).register(registry);
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
            lastCurrentMillis.set(System.currentTimeMillis());
        }
    }

    @Override
    public void eventAge(Duration age) {
        lastCurrentMillis.set(System.currentTimeMillis() - age.toMillis());
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
