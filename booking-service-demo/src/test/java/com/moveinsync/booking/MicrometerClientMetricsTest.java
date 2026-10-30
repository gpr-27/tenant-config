package com.moveinsync.booking;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerClientMetricsTest {

    @Test
    void prometheusScrapeUsesTheSpecMetricNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerClientMetrics metrics = new MicrometerClientMetrics(registry);

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
}
