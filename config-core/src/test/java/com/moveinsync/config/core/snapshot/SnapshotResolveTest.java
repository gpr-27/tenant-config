package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.error.UnknownSettingException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.scope.ScopePath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotResolveTest {

    private static final String CUTOFF = "booking.cutoff";

    private Snapshot snapshot;

    @BeforeEach
    void setUp() {
        Snapshot seeded = Seeds.snapshot();
        snapshot = seeded.apply(seeded.version() + 1, changeSet("acme",
                set(ScopeKey.client("acme"), CUTOFF, "\"PT45M\""),
                set(ScopeKey.office("blr-ecospace"), CUTOFF, "\"PT30M\""),
                set(ScopeKey.shift("night-A"), CUTOFF, "\"PT15M\"")));
    }

    @Test
    void shiftBeatsOfficeBeatsClientBeatsGlobal() {
        assertThat(snapshot.resolve("acme", CUTOFF, new ScopePath("acme", "blr-ecospace", "night-A")))
                .isEqualTo(new Resolved(Duration.ofMinutes(15), ScopeLevel.SHIFT, snapshot.version()));
        assertThat(snapshot.resolve("acme", CUTOFF, new ScopePath("acme", "blr-ecospace", "day-B")))
                .isEqualTo(new Resolved(Duration.ofMinutes(30), ScopeLevel.OFFICE, snapshot.version()));
        assertThat(snapshot.resolve("acme", CUTOFF, new ScopePath("acme", "hyd-hitec", null)))
                .isEqualTo(new Resolved(Duration.ofMinutes(45), ScopeLevel.CLIENT, snapshot.version()));
    }

    @Test
    void fallsBackToTheGlobalDefault() {
        Resolved resolved = snapshot.resolve("acme", "booking.maxAdvance", new ScopePath("acme", "blr-ecospace", null));

        assertThat(resolved).isEqualTo(new Resolved(Duration.ofHours(168), ScopeLevel.GLOBAL, snapshot.version()));
    }

    @Test
    void acmeOverrideIsInvisibleToGlobex() {
        Resolved resolved = snapshot.resolve("globex", CUTOFF, new ScopePath("globex", "blr-ecospace", "night-A"));

        assertThat(resolved.value()).isEqualTo(Duration.ofMinutes(60));
        assertThat(resolved.source()).isEqualTo(ScopeLevel.GLOBAL);
    }

    @Test
    void unknownKeyThrows() {
        assertThatThrownBy(() -> snapshot.resolve("acme", "booking.colour", new ScopePath("acme", null, null)))
                .isInstanceOf(UnknownSettingException.class);
    }

    @Test
    void getReturnsTheTypedValue() {
        Duration cutoff = snapshot.get("acme", CUTOFF, new ScopePath("acme", "blr-ecospace", null), Duration.class);

        assertThat(cutoff).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void overrideAtSeesOnlyThatExactScope() {
        assertThat(snapshot.overrideAt("acme", ScopeKey.office("blr-ecospace"), CUTOFF))
                .contains(Duration.ofMinutes(30));
        assertThat(snapshot.overrideAt("acme", ScopeKey.office("hyd-hitec"), CUTOFF)).isEmpty();
        assertThat(snapshot.overrideAt("globex", ScopeKey.client("globex"), CUTOFF)).isEmpty();
    }
}
