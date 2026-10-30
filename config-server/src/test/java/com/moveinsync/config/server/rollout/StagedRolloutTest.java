package com.moveinsync.config.server.rollout;

import com.jayway.jsonpath.JsonPath;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class StagedRolloutTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
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
    void stagedOfficesGetTheNewValueAndAThirdKeepsTheOld() throws Exception {
        stage().andExpect(status().isCreated())
                .andExpect(jsonPath("$.offices[0]").value("blr-ecospace"))
                .andExpect(jsonPath("$.offices[1]").value("hyd-hitec"));

        cutoffAt("blr-ecospace").andExpect(jsonPath("$.value").value("PT30M"));
        cutoffAt("hyd-hitec").andExpect(jsonPath("$.value").value("PT30M"));
        cutoffAt("pune-magarpatta").andExpect(jsonPath("$.value").value("PT1H"));
    }

    @Test
    void promoteMovesTheValueToClientLevelInExactlyOneVersion() throws Exception {
        long staged = JsonPath.parse(stage().andReturn().getResponse().getContentAsString()).read("$.version", Long.class);

        String promoted = mvc.perform(post("/api/v1/tenants/acme/changes/" + staged + "/promote")
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.parse(promoted).read("$.version", Long.class)).isEqualTo(staged + 1);
        assertThat(currentState.snapshot().version()).isEqualTo(staged + 1);
        cutoffAt("pune-magarpatta").andExpect(jsonPath("$.value").value("PT30M"))
                .andExpect(jsonPath("$.source").value("CLIENT"));
        cutoffAt("blr-ecospace").andExpect(jsonPath("$.source").value("CLIENT"));
        assertThat(currentState.snapshot().tenantOverrides("acme").keySet()).containsExactly(ScopeKey.client("acme"));
        assertThat(jdbc.queryForList("select distinct scope_level from config_override", String.class))
                .containsExactly(ScopeLevel.CLIENT.name());
    }

    @Test
    void anotherTenantsStagedChangeCannotBePromoted() throws Exception {
        long staged = JsonPath.parse(stage().andReturn().getResponse().getContentAsString()).read("$.version", Long.class);

        mvc.perform(post("/api/v1/tenants/globex/changes/" + staged + "/promote")
                        .header("Authorization", "Bearer " + jwt.issue("admin", Role.ADMIN, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VERSION_NOT_FOUND"));
    }

    private ResultActions stage() throws Exception {
        String body = """
                {"reason": "try 30m in two offices first", "targetOffices": ["blr-ecospace", "hyd-hitec"],
                 "operations": [{"key": "booking.cutoff", "value": "PT30M"}]}
                """;
        return mvc.perform(post("/api/v1/tenants/acme/changes/staged").header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions cutoffAt(String officeId) throws Exception {
        return mvc.perform(get("/api/v1/tenants/acme/resolve").param("key", "booking.cutoff")
                .param("officeId", officeId).param("explain", "true").header("Authorization", bearer()));
    }

    private String bearer() {
        return "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme");
    }
}
