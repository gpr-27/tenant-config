package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.SnapshotResponse;
import org.junit.jupiter.api.Test;

import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatTest {

    @Test
    void streamHeartbeatReachesTheMetricsAndChangesNothing() {
        FakeTransport transport = new FakeTransport();
        CountingMetrics metrics = new CountingMetrics();
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(43)));
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(), metrics,
                new ManualScheduler());
        client.start();

        transport.heartbeat();
        transport.heartbeat();

        assertThat(metrics.alives).isEqualTo(2);
        assertThat(client.version()).isEqualTo(43);
        assertThat(transport.snapshotRequests).containsExactly(0L);
    }
}
