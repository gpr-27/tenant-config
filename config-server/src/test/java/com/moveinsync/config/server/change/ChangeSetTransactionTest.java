package com.moveinsync.config.server.change;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.push.PushHub;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ChangeSetTransactionTest {

    @SpyBean
    OverrideRepository overrides;
    @SpyBean
    PushHub pushHub;

    @Autowired
    ChangeSetService changeSets;
    @Autowired
    CurrentState currentState;
    @Autowired
    RecentChanges recentChanges;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
        // reseeding publishes the eight definitions; only what the test itself does counts
        Mockito.clearInvocations(pushHub);
    }

    @Test
    void invalidSecondOperationWritesNothing() {
        long before = currentState.snapshot().version();
        ChangeSet changeSet = new ChangeSet("acme", "acme-ops", "two offices", List.of(
                cutoff("blr-ecospace", "PT30M"),
                cutoff("hyd-hitec", "PT-5M")));

        assertThatThrownBy(() -> changeSets.submit(changeSet, ChangeKind.CHANGE, null))
                .isInstanceOf(ValidationException.class);

        assertThat(logRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from config_override", Long.class)).isZero();
        assertThat(currentState.snapshot().version()).isEqualTo(before);
        verify(pushHub, never()).publish(any());
    }

    @Test
    void failingOverrideWriteLeavesNoLogRowAndPushesNothing() {
        long before = currentState.snapshot().version();
        doThrow(new DataAccessResourceFailureException("disk full"))
                .when(overrides).applyAll(any(), any(), anyLong());
        ChangeSet changeSet = new ChangeSet("acme", "acme-ops", "pilot", List.of(cutoff("blr-ecospace", "PT30M")));

        assertThatThrownBy(() -> changeSets.submit(changeSet, ChangeKind.CHANGE, null))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(logRows()).isEqualTo(before);
        assertThat(currentState.snapshot().version()).isEqualTo(before);
        assertThat(recentChanges.since(before).orElseThrow()).isEmpty();
        verify(pushHub, never()).publish(any());
    }

    @Test
    void versionBurnedByAFailedCommitIsReused() {
        long before = currentState.snapshot().version();
        doThrow(new DataAccessResourceFailureException("disk full"))
                .when(overrides).applyAll(any(), any(), anyLong());
        ChangeSet changeSet = new ChangeSet("acme", "acme-ops", "pilot", List.of(cutoff("blr-ecospace", "PT30M")));
        assertThatThrownBy(() -> changeSets.submit(changeSet, ChangeKind.CHANGE, null));
        Mockito.reset(overrides);

        assertThat(changeSets.submit(changeSet, ChangeKind.CHANGE, null)).isEqualTo(before + 1);
    }

    private long logRows() {
        return jdbc.queryForObject("select count(*) from config_change", Long.class);
    }

    private static Operation cutoff(String office, String value) {
        return new Operation(OpKind.SET, ScopeKey.office(office), "booking.cutoff", TextNode.valueOf(value));
    }
}
