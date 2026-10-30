package com.moveinsync.config.server.rollback;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.StateLoader;
import com.moveinsync.config.server.web.dto.RollbackResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(TestDatabase.class)
class RollbackLockTest {

    @SpyBean
    StateReplayer replayer;

    @Autowired
    RollbackService rollbacks;
    @Autowired
    ChangeSetService changeSets;
    @Autowired
    CurrentState currentState;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
    }

    @Test
    void changeArrivingWhileTheDiffIsComputedLandsAfterTheRollback() throws Exception {
        long target = set(ScopeKey.office("blr-ecospace"), "PT30M");
        set(ScopeKey.office("blr-ecospace"), "PT45M");

        // another operator's write starts exactly between "read current state" and "submit the diff"
        AtomicLong concurrentVersion = new AtomicLong();
        Thread concurrent = new Thread(() -> concurrentVersion.set(set(ScopeKey.office("hyd-hitec"), "PT20M")));
        doAnswer(invocation -> {
            concurrent.start();
            concurrent.join(500);
            return invocation.callRealMethod();
        }).when(replayer).stateAt(eq("acme"), anyLong());

        RollbackResponse rollback = rollbacks.rollback("acme", target, false, "acme-ops");
        concurrent.join();

        assertThat(concurrentVersion.get()).isEqualTo(rollback.version() + 1);
        assertThat(currentState.snapshot().overrideAt("acme", ScopeKey.office("blr-ecospace"), "booking.cutoff"))
                .contains(Duration.ofMinutes(30));
        assertThat(currentState.snapshot().overrideAt("acme", ScopeKey.office("hyd-hitec"), "booking.cutoff"))
                .contains(Duration.ofMinutes(20));
    }

    private long set(ScopeKey scope, String cutoff) {
        Operation op = new Operation(OpKind.SET, scope, "booking.cutoff", TextNode.valueOf(cutoff));
        return changeSets.submit(new ChangeSet("acme", "acme-ops", "history", List.of(op)), ChangeKind.CHANGE, null);
    }
}
