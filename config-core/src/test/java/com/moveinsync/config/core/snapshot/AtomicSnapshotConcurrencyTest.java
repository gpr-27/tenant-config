package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopePath;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static org.assertj.core.api.Assertions.assertThat;

class AtomicSnapshotConcurrencyTest {

    private static final ScopeKey ACME = ScopeKey.client("acme");
    private static final ScopePath PATH = new ScopePath("acme", "blr-ecospace", null);
    private static final ChangeSet SHORT = pair("PT30M", "PT1H");
    private static final ChangeSet LONG = pair("PT2H", "PT4H");

    @Test
    void readerNeverSeesNewCutoffWithOldMaxAdvance() throws InterruptedException {
        Snapshot seeded = Seeds.snapshot();
        AtomicReference<Snapshot> current = new AtomicReference<>(seeded.apply(seeded.version() + 1, SHORT));
        AtomicBoolean writerDone = new AtomicBoolean();
        AtomicLong reads = new AtomicLong();
        AtomicLong violations = new AtomicLong();
        CountDownLatch readersStarted = new CountDownLatch(8);

        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread reader = new Thread(() -> {
                readersStarted.countDown();
                while (!writerDone.get()) {
                    Snapshot snapshot = current.get();
                    Duration cutoff = snapshot.get("acme", "booking.cutoff", PATH, Duration.class);
                    Duration maxAdvance = snapshot.get("acme", "booking.maxAdvance", PATH, Duration.class);
                    if (cutoff.compareTo(maxAdvance) >= 0) {
                        violations.incrementAndGet();
                    }
                    reads.incrementAndGet();
                }
            });
            reader.start();
            readers.add(reader);
        }

        readersStarted.await();
        for (int i = 0; i < 10_000; i++) {
            Snapshot snapshot = current.get();
            current.set(snapshot.apply(snapshot.version() + 1, i % 2 == 0 ? LONG : SHORT));
        }
        writerDone.set(true);
        for (Thread reader : readers) {
            reader.join();
        }

        assertThat(violations.get()).isZero();
        assertThat(reads.get()).isPositive();
        assertThat(current.get().version()).isEqualTo(seeded.version() + 1 + 10_000);
    }

    // the negative control: the guarantee comes from reading one snapshot, not from the values themselves
    @Test
    void readingKeysFromTwoDifferentSnapshotsCanMix() {
        Snapshot seeded = Seeds.snapshot();
        Snapshot b = seeded.apply(seeded.version() + 1, SHORT);
        Snapshot a = b.apply(b.version() + 1, LONG);

        Duration cutoff = a.get("acme", "booking.cutoff", PATH, Duration.class);
        Duration maxAdvance = b.get("acme", "booking.maxAdvance", PATH, Duration.class);

        assertThat(cutoff).isGreaterThanOrEqualTo(maxAdvance);
    }

    private static ChangeSet pair(String cutoff, String maxAdvance) {
        return changeSet("acme",
                set(ACME, "booking.cutoff", "\"" + cutoff + "\""),
                set(ACME, "booking.maxAdvance", "\"" + maxAdvance + "\""));
    }
}
