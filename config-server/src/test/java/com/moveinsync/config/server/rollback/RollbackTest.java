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
import com.moveinsync.config.server.security.JwtService;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class RollbackTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    ChangeSetService changeSets;
    @Autowired
    StateReplayer replayer;
    @Autowired
    CurrentState currentState;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    long target;
    long latest;

    @BeforeEach
    void historyOfThreeAcmeChanges() {
        DatabaseReset.reset(jdbc, stateLoader);
        target = set("acme", ScopeKey.office("blr-ecospace"), "booking.cutoff", "PT30M");
        set("acme", ScopeKey.office("blr-ecospace"), "booking.cutoff", "PT45M");
        latest = set("acme", ScopeKey.client("acme"), "booking.maxAdvance", "PT200H");
    }

    @Test
    void dryRunShowsTheExactDiffAndWritesNothing() throws Exception {
        rollback("acme", target, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(content().json("""
                        {"targetVersion": %d, "diff": [
                          {"op": "UNSET", "level": "CLIENT", "scopeId": "acme", "key": "booking.maxAdvance"},
                          {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}
                        ]}""".formatted(target), true));

        assertThat(currentState.snapshot().version()).isEqualTo(latest);
    }

    @Test
    void applyMakesTheTenantStateEqualTheTargetAsANewVersion() throws Exception {
        rollback("acme", target, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(latest + 1));

        assertThat(currentState.snapshot().version()).isEqualTo(latest + 1);
        assertThat(currentState.snapshot().tenantOverrides("acme")).isEqualTo(replayer.stateAt("acme", target));
        assertThat(jdbc.queryForObject("select kind from config_change where version = ?", String.class, latest + 1))
                .isEqualTo("ROLLBACK");
    }

    @Test
    void noTenantChangeSinceTargetGivesAnEmptyDiffAndNoVersion() throws Exception {
        long globex = set("globex", ScopeKey.client("globex"), "booking.cutoff", "PT20M");

        rollback("acme", latest, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diff.length()").value(0))
                .andExpect(jsonPath("$.version").doesNotExist());

        assertThat(currentState.snapshot().version()).isEqualTo(globex);
    }

    @Test
    void targetAboveTheCurrentVersionIs404() throws Exception {
        rollback("acme", latest + 50, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"));
    }

    private long set(String tenant, ScopeKey scope, String key, String value) {
        Operation op = new Operation(OpKind.SET, scope, key, TextNode.valueOf(value));
        return changeSets.submit(new ChangeSet(tenant, tenant + "-ops", "history", List.of(op)), ChangeKind.CHANGE, null);
    }

    private ResultActions rollback(String tenant, long targetVersion, boolean dryRun) throws Exception {
        return mvc.perform(post("/api/v1/tenants/" + tenant + "/rollback")
                .header("Authorization", "Bearer " + jwt.issue(tenant + "-ops", Role.OPERATOR, tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetVersion\": " + targetVersion + ", \"dryRun\": " + dryRun + "}"));
    }
}
