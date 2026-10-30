package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.SnapshotResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.cutoffIn;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigClientReadTest {

    @Test
    void readsNeverTouchTransport() {
        FakeTransport transport = new FakeTransport();
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(43).apply(cutoff(44, "PT30M"))));
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(),
                new CountingMetrics(), new ManualScheduler());
        client.start();
        int callsAfterStart = transport.calls;

        transport.down = true;
        for (int i = 0; i < 10_000; i++) {
            assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(30));
            assertThat(client.explain("acme", "booking.cutoff", Fixtures.BLR).version()).isEqualTo(44);
        }

        assertThat(transport.calls).isEqualTo(callsAfterStart);
    }
}
