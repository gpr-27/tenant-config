package com.moveinsync.config.server.state;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.scope.ScopeKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecentChangesTest {

    @Test
    void oldestEventIsEvictedAtCapacity() {
        RecentChanges ring = ringHolding(3, 1, 2, 3, 4, 5);

        assertThat(ring.size()).isEqualTo(3);
        assertThat(versions(ring.since(2).orElseThrow())).containsExactly(3L, 4L, 5L);
    }

    @Test
    void sinceJustBeforeOldestIsStillCovered() {
        RecentChanges ring = ringHolding(3, 1, 2, 3, 4, 5);

        assertThat(ring.since(2)).isPresent();
        assertThat(ring.since(1)).isEmpty();
    }

    @Test
    void sinceNewestOrLaterIsAnEmptyDelta() {
        RecentChanges ring = ringHolding(3, 1, 2, 3, 4, 5);

        assertThat(ring.since(5).orElseThrow()).isEmpty();
        assertThat(ring.since(9).orElseThrow()).isEmpty();
        assertThat(versions(ring.since(4).orElseThrow())).containsExactly(5L);
    }

    @Test
    void emptyRingCoversVersionZero() {
        assertThat(new RecentChanges(3).since(0).orElseThrow()).isEmpty();
    }

    private static RecentChanges ringHolding(int capacity, long... versions) {
        RecentChanges ring = new RecentChanges(capacity);
        Operation op = new Operation(OpKind.UNSET, ScopeKey.office("blr"), "booking.cutoff", null);
        for (long version : versions) {
            ChangeSet changeSet = new ChangeSet("acme", "acme-ops", "test", List.of(op));
            ring.add(new ChangeEvent(version, ChangeKind.CHANGE, new OverridesChanged(changeSet), Instant.EPOCH));
        }
        return ring;
    }

    private static List<Long> versions(List<ChangeEvent> events) {
        return events.stream().map(ChangeEvent::version).toList();
    }
}
