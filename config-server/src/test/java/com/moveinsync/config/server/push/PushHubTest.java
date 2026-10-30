package com.moveinsync.config.server.push;

import com.moveinsync.config.server.metrics.ServerMetrics;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushHubTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CurrentState currentState = new CurrentState();
    private final RecentChanges recentChanges = new RecentChanges(10);
    private final PushHub hub = new PushHub(recentChanges, currentState,
            new ServerMetrics(registry, currentState, recentChanges), 1);

    @AfterEach
    void closeHub() {
        hub.close();
    }

    @Test
    void subscriberBeyondTheConnectionCapIsRefused() {
        hub.subscribe(0);

        assertThatThrownBy(() -> hub.subscribe(0)).isInstanceOf(TooManyConnectionsException.class);
        assertThat(hub.connections()).isEqualTo(1);
    }

    @Test
    void queueDepthIsExportedAsAGauge() {
        assertThat(registry.get("config.push.queue.depth").gauge().value()).isZero();
    }
}
