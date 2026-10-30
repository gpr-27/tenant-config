package com.moveinsync.config.server.change;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ConcurrentSubmitTest {

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
    }

    @Test
    void twentyParallelSubmitsGetTwentyConsecutiveVersions() throws Exception {
        long before = currentState.snapshot().version();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ChangeSet changeSet = cutoffAtOffice("office-" + i);
            results.add(pool.submit(() -> {
                start.await();
                return changeSets.submit(changeSet, ChangeKind.CHANGE, null);
            }));
        }
        start.countDown();

        List<Long> versions = new ArrayList<>();
        for (Future<Long> result : results) {
            versions.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        List<Long> expected = new ArrayList<>();
        for (long v = before + 1; v <= before + 20; v++) {
            expected.add(v);
        }
        assertThat(versions).containsExactlyInAnyOrderElementsOf(expected);
        List<Long> ringOrder = recentChanges.since(before).orElseThrow().stream().map(ChangeEvent::version).toList();
        assertThat(ringOrder).containsExactlyElementsOf(expected);
        assertThat(currentState.snapshot().version()).isEqualTo(before + 20);
        assertThat(jdbc.queryForObject("select max(version) from config_change", Long.class)).isEqualTo(before + 20);
    }

    private static ChangeSet cutoffAtOffice(String office) {
        Operation op = new Operation(OpKind.SET, ScopeKey.office(office), "booking.cutoff", TextNode.valueOf("PT30M"));
        return new ChangeSet("acme", "acme-ops", "parallel " + office, List.of(op));
    }
}
