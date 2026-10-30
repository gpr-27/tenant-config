package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.SnapshotResponse;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.cutoffIn;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class VersionMonotonicityTest {

    private final FakeTransport transport = new FakeTransport();
    private final CountingMetrics metrics = new CountingMetrics();
    private final List<Long> seen = new ArrayList<>();
    private ConfigClient client;

    @BeforeEach
    void startAt43() {
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(43)));
        client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(), metrics, new ManualScheduler());
        client.start();
        client.addListener((Snapshot s) -> seen.add(s.version()));
        assertThat(client.version()).isEqualTo(43);
    }

    @Test
    void eventWithVersion41After43IsDropped() {
        transport.push(cutoff(41, "PT5M"));

        assertThat(client.version()).isEqualTo(43);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(60));
        assertThat(metrics.staleEvents).isEqualTo(1);
        assertThat(transport.snapshotRequests).containsExactly(0L);
        assertThat(seen).isEmpty();
    }

    @Test
    void duplicateEventIsDropped() {
        transport.push(cutoff(44, "PT30M"));
        transport.push(cutoff(44, "PT30M"));

        assertThat(client.version()).isEqualTo(44);
        assertThat(metrics.staleEvents).isEqualTo(1);
        assertThat(seen).containsExactly(44L);
    }

    @Test
    void gapTriggersResyncAndAppliesInOrder() {
        transport.answers.add(new SnapshotResponse.Delta(43, 45, List.of(cutoff(44, "PT30M"), cutoff(45, "PT20M"))));

        transport.push(cutoff(45, "PT20M"));

        assertThat(metrics.gapResyncs).isEqualTo(1);
        assertThat(transport.snapshotRequests).containsExactly(0L, 43L);
        assertThat(client.version()).isEqualTo(45);
        // 45 applied after 44, so its value is the one left standing
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void deltaSkipsWhatIsAlreadyAppliedAndStopsAtAGap() {
        transport.push(cutoff(44, "PT30M"));
        transport.answers.add(new SnapshotResponse.Delta(43, 47, List.of(cutoff(44, "PT30M"), cutoff(45, "PT20M"),
                cutoff(47, "PT10M"))));
        transport.answers.add(new SnapshotResponse.Delta(45, 47, List.of(cutoff(46, "PT15M"), cutoff(47, "PT10M"))));

        transport.askForResync();

        assertThat(transport.snapshotRequests).containsExactly(0L, 44L, 45L);
        assertThat(client.version()).isEqualTo(47);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(10));
    }
}
