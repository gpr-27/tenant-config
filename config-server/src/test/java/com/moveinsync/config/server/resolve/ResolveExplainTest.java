package com.moveinsync.config.server.resolve;

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
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ResolveExplainTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    ChangeSetService changeSets;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    long pilotVersion;

    @BeforeEach
    void bengaluruOfficeHasThirtyMinutes() {
        DatabaseReset.reset(jdbc, stateLoader);
        Operation op = new Operation(OpKind.SET, ScopeKey.office("blr-ecospace"), "booking.cutoff",
                TextNode.valueOf("PT30M"));
        pilotVersion = changeSets.submit(new ChangeSet("acme", "acme-ops", "pilot", List.of(op)), ChangeKind.CHANGE, null);
    }

    @Test
    void officeValueWins() throws Exception {
        resolve("blr-ecospace", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("booking.cutoff"))
                .andExpect(jsonPath("$.value").value("PT30M"))
                .andExpect(jsonPath("$.source").value("OFFICE"))
                .andExpect(jsonPath("$.version").value(pilotVersion));
    }

    @Test
    void otherOfficeFallsBackToTheGlobalDefault() throws Exception {
        resolve("hyd-hitec", true)
                .andExpect(jsonPath("$.value").value("PT1H"))
                .andExpect(jsonPath("$.source").value("GLOBAL"));
    }

    @Test
    void explainFieldsArePresentOnlyWhenAsked() throws Exception {
        resolve("blr-ecospace", false)
                .andExpect(jsonPath("$.value").value("PT30M"))
                .andExpect(jsonPath("$.source").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void unknownKeyIs404() throws Exception {
        mvc.perform(get("/api/v1/tenants/acme/resolve").param("key", "booking.colour").header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_SETTING"));
    }

    private ResultActions resolve(String officeId, boolean explain) throws Exception {
        return mvc.perform(get("/api/v1/tenants/acme/resolve")
                .param("key", "booking.cutoff")
                .param("officeId", officeId)
                .param("explain", String.valueOf(explain))
                .header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwt.issue("acme-emp", Role.EMPLOYEE, "acme");
    }
}
