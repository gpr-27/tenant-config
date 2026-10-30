package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.SnapshotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.cutoffIn;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class FullSnapshotReplacesStateTest {

    private final FakeTransport transport = new FakeTransport();
    private final InMemoryStore store = new InMemoryStore();
    private ConfigClient client;

    @BeforeEach
    void startAt43WithA30MinuteCutoff() {
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(42).apply(cutoff(43, "PT30M"))));
        client = new ConfigClient(Fixtures.options(), transport, store, new CountingMetrics(), new ManualScheduler());
        client.start();
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void fullWithHigherVersionReplacesTheSnapshot() {
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(50)));

        transport.askForResync();

        assertThat(client.version()).isEqualTo(50);
        // replaced whole, not merged: the override that only the old snapshot had is gone
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(60));
        assertThat(store.saved.version()).isEqualTo(50);
    }

    @Test
    void fullWithLowerVersionReplacesTheSnapshot() {
        // the server was restored from an older backup: it is the truth, and later pushes continue from its version
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(40).apply(cutoff(41, "PT45M"))));

        transport.askForResync();
        assertThat(client.version()).isEqualTo(41);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(45));

        transport.push(cutoff(42, "PT15M"));
        assertThat(client.version()).isEqualTo(42);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(15));
    }
}
