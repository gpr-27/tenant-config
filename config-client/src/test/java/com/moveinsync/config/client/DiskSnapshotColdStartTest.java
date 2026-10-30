package com.moveinsync.config.client;

import com.moveinsync.config.client.store.FileSnapshotStore;
import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.cutoffIn;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiskSnapshotColdStartTest {

    @TempDir
    Path dir;

    private final FakeTransport transport = new FakeTransport();
    private final ManualScheduler scheduler = new ManualScheduler();

    @Test
    void clientServesSnapshotWhileServerIsDown() {
        Path file = dir.resolve("snapshot.json");
        new FileSnapshotStore(file).save(at150());
        transport.down = true;

        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new FileSnapshotStore(file),
                new CountingMetrics(), scheduler);
        client.start();
        scheduler.runPeriodic();

        assertThat(client.version()).isEqualTo(150);
        assertThat(cutoffIn(client)).isEqualTo(Duration.ofMinutes(30));
        // the stream failed to open, so a jittered reconnect is waiting
        assertThat(scheduler.delaysMillis).hasSize(1);
    }

    @Test
    void corruptFileIsIgnored() throws IOException {
        Path file = dir.resolve("snapshot.json");
        Files.writeString(file, "{\"version\": 150, \"definitions\": [{\"key\": \"booking.cut");
        assertThat(new FileSnapshotStore(file).load()).isEmpty();

        Files.writeString(file, "");
        assertThat(new FileSnapshotStore(file).load()).isEmpty();

        transport.down = true;
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new FileSnapshotStore(file),
                new CountingMetrics(), scheduler);
        client.start();
        assertThat(client.version()).isZero();
    }

    @Test
    void saveIsAtomic() {
        Path file = dir.resolve("snapshot.json");
        new FileSnapshotStore(file).save(at150());
        SnapshotStore crashesBeforeRename = new FileSnapshotStore(file) {
            @Override
            protected void moveIntoPlace(Path from, Path to) throws IOException {
                throw new IOException("power cut");
            }
        };

        assertThatThrownBy(() -> crashesBeforeRename.save(at150().apply(cutoff(151, "PT20M"))))
                .isInstanceOf(UncheckedIOException.class);

        Snapshot onDisk = new FileSnapshotStore(file).load().orElseThrow();
        assertThat(onDisk.version()).isEqualTo(150);
    }

    @Test
    void failedSaveDoesNotStopTheUpdate() {
        SnapshotStore failing = new SnapshotStore() {
            @Override
            public Optional<Snapshot> load() {
                return Optional.of(at150());
            }

            @Override
            public void save(Snapshot snapshot) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
        };
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, failing, new CountingMetrics(), scheduler);
        client.start();

        transport.push(cutoff(151, "PT20M"));

        assertThat(client.version()).isEqualTo(151);
    }

    @Test
    void anyStoreFailureStillLetsListenersSeeTheUpdate() {
        SnapshotStore broken = new SnapshotStore() {
            @Override
            public Optional<Snapshot> load() {
                return Optional.of(at150());
            }

            @Override
            public void save(Snapshot snapshot) {
                throw new IllegalStateException("store closed");
            }
        };
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, broken, new CountingMetrics(), scheduler);
        List<Long> seen = new ArrayList<>();
        client.addListener(snapshot -> seen.add(snapshot.version()));
        client.start();

        transport.push(cutoff(151, "PT20M"));

        assertThat(client.version()).isEqualTo(151);
        assertThat(seen).contains(151L);
    }

    private static Snapshot at150() {
        return snapshotAt(149).apply(cutoff(150, "PT30M"));
    }
}
