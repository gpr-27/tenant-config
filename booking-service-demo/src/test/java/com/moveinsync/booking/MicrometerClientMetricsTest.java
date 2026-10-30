package com.moveinsync.booking;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerClientMetricsTest {

    @Test
    void prometheusScrapeUsesTheSpecMetricNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerClientMetrics metrics = new MicrometerClientMetrics(registry, () -> 1_000_000L);

        metrics.version(42);
        metrics.lag(3);
        metrics.eventAge(Duration.ofMillis(1500));
        metrics.reconnect();
        metrics.staleEvent();
        metrics.gapResync();
        metrics.readTimer(2_000);

        String scrape = registry.scrape();
        assertThat(scrape)
                .contains("config_client_version 42.0")
                .contains("config_client_lag_versions 3.0")
                .contains("config_client_lag_seconds 1.5")
                .contains("config_client_reconnects_total 1.0")
                .contains("config_client_stale_events_total 1.0")
                .contains("config_client_gap_resync_total 1.0")
                .contains("config_client_read_latency_seconds_count 1");
    }

    @Test
    void lagSecondsIsZeroAfterAHeartbeatAndGrowsWhileNothingArrives() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong now = new AtomicLong(1_000_000);
        MicrometerClientMetrics metrics = new MicrometerClientMetrics(registry, now::get);
        now.addAndGet(40_000);

        metrics.alive();
        assertThat(lagSeconds(registry)).isZero();

        now.addAndGet(20_000);
        assertThat(lagSeconds(registry)).isEqualTo(20.0);

        metrics.alive();
        assertThat(lagSeconds(registry)).isZero();
    }

    @Test
    void healPollWithNoLagAlsoCountsAsCurrent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong now = new AtomicLong(1_000_000);
        MicrometerClientMetrics metrics = new MicrometerClientMetrics(registry, now::get);

        now.addAndGet(30_000);
        metrics.lag(2);
        assertThat(lagSeconds(registry)).isEqualTo(30.0);

        metrics.lag(0);
        assertThat(lagSeconds(registry)).isZero();
    }

    private static double lagSeconds(SimpleMeterRegistry registry) {
        return registry.get("config_client_lag_seconds").gauge().value();
    }
}
