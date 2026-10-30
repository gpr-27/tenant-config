package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.DefinitionChanged;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.scope.ScopeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static com.moveinsync.config.core.Seeds.unset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotApplyTest {

    private static final ScopeKey BLR = ScopeKey.office("blr-ecospace");
    private static final ScopeKey HYD = ScopeKey.office("hyd-hitec");

    private Snapshot before;

    @BeforeEach
    void setUp() {
        Snapshot seeded = Seeds.snapshot();
        Snapshot withAcme = seeded.apply(seeded.version() + 1, changeSet("acme",
                set(BLR, "booking.cutoff", "\"PT30M\""),
                set(HYD, "booking.cutoff", "\"PT45M\"")));
        before = withAcme.apply(withAcme.version() + 1, changeSet("globex",
                set(ScopeKey.client("globex"), "cab.types", "[\"SEDAN\"]")));
    }

    @Test
    void returnsNewInstanceAndLeavesTheOldOneUnchanged() {
        Snapshot after = before.apply(before.version() + 1, changeSet("acme", set(BLR, "booking.cutoff", "\"PT20M\"")));

        assertThat(after).isNotSameAs(before);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.overrideAt("acme", BLR, "booking.cutoff")).contains(Duration.ofMinutes(20));
        assertThat(before.overrideAt("acme", BLR, "booking.cutoff")).contains(Duration.ofMinutes(30));
    }

    @Test
    void storesTheParsedJavaValue() {
        Snapshot after = before.apply(before.version() + 1, changeSet("acme",
                set(BLR, "booking.maxActive", "5")));

        assertThat(after.overrideAt("acme", BLR, "booking.maxActive")).contains(new BigDecimal(5));
        assertThat(before.overrideAt("globex", ScopeKey.client("globex"), "cab.types")).contains(List.of("SEDAN"));
    }

    @Test
    void unsetRemovesTheOverride() {
        Snapshot after = before.apply(before.version() + 1, changeSet("acme", unset(BLR, "booking.cutoff")));

        assertThat(after.overrideAt("acme", BLR, "booking.cutoff")).isEmpty();
        assertThat(after.tenantOverrides("acme")).doesNotContainKey(BLR);
    }

    @Test
    void unsetOfAMissingKeyIsANoOp() {
        Snapshot after = before.apply(before.version() + 1, changeSet("acme", unset(BLR, "night.window")));

        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.tenantOverrides("acme")).isEqualTo(before.tenantOverrides("acme"));
    }

    @Test
    void versionMustBeExactlyOneMore() {
        var changeSet = changeSet("acme", set(BLR, "booking.cutoff", "\"PT20M\""));

        assertThatThrownBy(() -> before.apply(before.version() + 2, changeSet))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> before.apply(before.version(), changeSet))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void untouchedTenantMapsAreTheSameObject() {
        Snapshot after = before.apply(before.version() + 1, changeSet("acme", set(BLR, "booking.cutoff", "\"PT20M\"")));

        assertThat(after.tenantOverrides("globex")).isSameAs(before.tenantOverrides("globex"));
        assertThat(after.tenantOverrides("acme").get(HYD)).isSameAs(before.tenantOverrides("acme").get(HYD));
        assertThat(after.tenantOverrides("acme").get(BLR)).isNotSameAs(before.tenantOverrides("acme").get(BLR));
        assertThat(after.definitions()).isSameAs(before.definitions());
    }

    @Test
    void eventWithOverridesPayloadAppliesTheChangeSet() {
        ChangeEvent event = new ChangeEvent(before.version() + 1, ChangeKind.CHANGE,
                new OverridesChanged(changeSet("acme", set(BLR, "booking.cutoff", "\"PT20M\""))), Instant.now());

        Snapshot after = before.apply(event);

        assertThat(after.overrideAt("acme", BLR, "booking.cutoff")).contains(Duration.ofMinutes(20));
    }

    @Test
    void eventWithDefinitionPayloadReplacesTheDefinitionAndKeepsOverrides() {
        SettingDefinition longerRide = new SettingDefinition("ride.maxDuration", SettingTypes.DURATION,
                Rules.NONE, Duration.ofHours(3), "Longest allowed ride");
        ChangeEvent event = new ChangeEvent(before.version() + 1, ChangeKind.DEFINITION,
                new DefinitionChanged(longerRide), Instant.now());

        Snapshot after = before.apply(event);

        assertThat(after.definitions().get("ride.maxDuration")).isEqualTo(longerRide);
        assertThat(after.tenantOverrides("acme")).isSameAs(before.tenantOverrides("acme"));
        assertThatThrownBy(() -> before.withDefinition(before.version() + 3, longerRide))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
