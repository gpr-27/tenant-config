package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.SnapshotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.cutoffIn;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class MissedPushHealTest {

    private final FakeTransport transport = new FakeTransport();
    private final CountingMetrics metrics = new CountingMetrics();
    private final ManualScheduler scheduler = new ManualScheduler();
    private ConfigClient client;

    @BeforeEach
    void startAt143() {
        transport.serverVersion = 143;
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(143)));
        client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(), metrics, scheduler);
        client.start();
    }

    @Test
    void lostPushIsHealedByThePoll() {
        // the server commits 144 but the push never arrives
        transport.serverVersion = 144;
        transport.answers.add(new SnapshotResponse.Delta(143, 144, List.of(cutoff(144, "PT30M"))));

        scheduler.runPeriodic();

        assertThat(metrics.lag).isEqualTo(1);
        assertThat(client.version()).isEqualTo(144);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void pollAtTheSameVersionFetchesNothing() {
        scheduler.runPeriodic();

        assertThat(metrics.lag).isZero();
        assertThat(transport.snapshotRequests).containsExactly(0L);
    }

    @Test
    void pollKeepsRunningWhileTheServerIsDown() {
        transport.down = true;
        scheduler.runPeriodic();

        transport.down = false;
        transport.serverVersion = 144;
        transport.answers.add(new SnapshotResponse.Delta(143, 144, List.of(cutoff(144, "PT30M"))));
        scheduler.runPeriodic();

        assertThat(client.version()).isEqualTo(144);
    }
}
