package com.moveinsync.config.server.definition;

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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class DefinitionImpactTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
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
    void maxAdvanceDefaultAtOrBelowTheDefaultCutoffIsRefused() throws Exception {
        long before = currentState.snapshot().version();

        setDefault("booking.maxAdvance", "PT1H")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("CROSS_FIELD_ORDER"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("at GLOBAL")))
                .andExpect(content().string(not(containsString("PT60M"))));

        assertThat(currentState.snapshot().version()).isEqualTo(before);
        assertThat(currentState.snapshot().definitions().get("booking.maxAdvance").defaultValue())
                .isEqualTo(Duration.ofHours(168));
    }

    @Test
    void maxAdvanceDefaultBelowAnOfficeCutoffIsRefused() throws Exception {
        set(ScopeKey.office("blr-ecospace"), "booking.cutoff", "PT3H");

        setDefault("booking.maxAdvance", "PT2H")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("CROSS_FIELD_ORDER"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("acme/OFFICE/blr-ecospace")))
                .andExpect(content().string(not(containsString("PT3H"))));
    }

    @Test
    void cutoffDefaultAboveAClientMaxAdvanceIsRefused() throws Exception {
        set(ScopeKey.client("acme"), "booking.maxAdvance", "PT2H");

        setDefault("booking.cutoff", "PT3H")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("CROSS_FIELD_ORDER"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("acme/CLIENT/acme")));
    }

    @Test
    void definitionChangeThatKeepsEveryPairInOrderIsAccepted() throws Exception {
        set(ScopeKey.office("blr-ecospace"), "booking.cutoff", "PT3H");

        setDefault("booking.maxAdvance", "PT4H").andExpect(status().isOk());
    }

    private void set(ScopeKey scope, String key, String value) {
        Operation op = new Operation(OpKind.SET, scope, key, TextNode.valueOf(value));
        changeSets.submit(new ChangeSet("acme", "acme-ops", "setup", List.of(op)), ChangeKind.CHANGE, null);
    }

    private ResultActions setDefault(String key, String value) throws Exception {
        return mvc.perform(put("/api/v1/definitions/" + key)
                .header("Authorization", "Bearer " + jwt.issue("admin", Role.ADMIN, null))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"default\": \"" + value + "\"}"));
    }
}
