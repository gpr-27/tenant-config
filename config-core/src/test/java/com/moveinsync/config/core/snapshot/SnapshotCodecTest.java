package com.moveinsync.config.core.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.scope.ScopeKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotCodecTest {

    @Test
    void jsonRoundTripGivesAnEqualSnapshot() throws Exception {
        Snapshot seeded = Seeds.snapshot();
        Snapshot acme = seeded.apply(seeded.version() + 1, changeSet("acme",
                set(ScopeKey.office("blr-ecospace"), "booking.cutoff", "\"PT30M\""),
                set(ScopeKey.shift("night-A"), "night.window", "{\"start\":\"21:00\",\"end\":\"05:00\",\"overnight\":true}"),
                set(ScopeKey.client("acme"), "booking.maxActive", "5")));
        Snapshot original = acme.apply(acme.version() + 1, changeSet("globex",
                set(ScopeKey.client("globex"), "cab.types", "[\"SEDAN\",\"TEMPO\"]"),
                set(ScopeKey.client("globex"), "escort.required", "\"true\"")));

        String text = SnapshotCodec.mapper().writeValueAsString(SnapshotCodec.toJson(original));
        Snapshot copy = SnapshotCodec.fromJson(SnapshotCodec.mapper().readTree(text));

        assertThat(copy.version()).isEqualTo(original.version());
        assertThat(copy.definitions()).isEqualTo(original.definitions());
        assertThat(copy.tenantIds()).isEqualTo(Set.of("acme", "globex"));
        assertThat(copy.tenantOverrides("acme")).isEqualTo(original.tenantOverrides("acme"));
        assertThat(copy.tenantOverrides("globex")).isEqualTo(original.tenantOverrides("globex"));
    }

    @Test
    void writesTheDocumentedShape() {
        Snapshot seeded = Seeds.snapshot();
        Snapshot snapshot = seeded.apply(seeded.version() + 1, changeSet("acme",
                set(ScopeKey.office("blr-ecospace"), "booking.cutoff", "\"PT30M\"")));

        JsonNode json = SnapshotCodec.toJson(snapshot);

        assertThat(json.get("version").asLong()).isEqualTo(snapshot.version());
        assertThat(json.get("definitions")).hasSize(8);
        assertThat(json.get("overrides")).containsExactly(Seeds.json("""
                {"tenantId":"acme","level":"OFFICE","scopeId":"blr-ecospace","key":"booking.cutoff","value":"PT30M"}
                """));
    }

    @Test
    void emptySnapshotRoundTrips() {
        Snapshot copy = SnapshotCodec.fromJson(SnapshotCodec.toJson(Snapshot.empty()));

        assertThat(copy.version()).isZero();
        assertThat(copy.definitions()).isEmpty();
        assertThat(copy.tenantIds()).isEmpty();
    }
}
