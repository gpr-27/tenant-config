package com.moveinsync.config.core.change;

import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static com.moveinsync.config.core.Seeds.unset;
import static org.assertj.core.api.Assertions.assertThat;

class ChangeEventCodecTest {

    @Test
    void overridesEventRoundTrips() throws Exception {
        ChangeEvent event = new ChangeEvent(144, ChangeKind.CHANGE, new OverridesChanged(changeSet("acme",
                set(ScopeKey.office("blr-ecospace"), "booking.cutoff", "\"PT30M\""),
                unset(ScopeKey.client("acme"), "cab.types"))), Instant.parse("2026-10-30T10:15:30Z"));

        String text = SnapshotCodec.mapper().writeValueAsString(ChangeEventCodec.toJson(event));

        assertThat(ChangeEventCodec.fromJson(SnapshotCodec.mapper().readTree(text))).isEqualTo(event);
    }

    @Test
    void definitionEventRoundTrips() {
        ChangeEvent event = new ChangeEvent(9, ChangeKind.DEFINITION,
                new DefinitionChanged(Seeds.definition("night.window")), Instant.parse("2026-10-30T10:15:30Z"));

        assertThat(ChangeEventCodec.fromJson(ChangeEventCodec.toJson(event))).isEqualTo(event);
    }
}
