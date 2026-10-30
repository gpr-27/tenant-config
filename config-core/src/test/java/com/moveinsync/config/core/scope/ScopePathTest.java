package com.moveinsync.config.core.scope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopePathTest {

    @Test
    void walksShiftThenOfficeThenClient() {
        ScopePath path = new ScopePath("acme", "blr-ecospace", "night-A");

        assertThat(path.fromMostSpecific()).containsExactly(
                ScopeKey.shift("night-A"), ScopeKey.office("blr-ecospace"), ScopeKey.client("acme"));
    }

    @Test
    void nullsAreSkipped() {
        assertThat(new ScopePath("acme", null, "night-A").fromMostSpecific())
                .containsExactly(ScopeKey.shift("night-A"), ScopeKey.client("acme"));
        assertThat(new ScopePath("acme", "blr-ecospace", null).fromMostSpecific())
                .containsExactly(ScopeKey.office("blr-ecospace"), ScopeKey.client("acme"));
        assertThat(new ScopePath("acme", null, null).fromMostSpecific())
                .containsExactly(ScopeKey.client("acme"));
    }

    @Test
    void globalIsNeverInThePath() {
        assertThat(new ScopePath("acme", "blr-ecospace", "night-A").fromMostSpecific())
                .extracting(ScopeKey::level)
                .doesNotContain(ScopeLevel.GLOBAL);
    }
}
