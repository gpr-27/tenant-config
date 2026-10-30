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
import io.micrometer.core.instrument.MeterRegistry;
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
    @Autowired
    MeterRegistry registry;

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

    @Test
    void officesThatDisagreeOnAValueCannotBePromoted() throws Exception {
        String body = """
                {"reason": "two offices, two values", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"},
                  {"op": "SET", "level": "OFFICE", "scopeId": "hyd-hitec", "key": "booking.cutoff", "value": "PT45M"}]}
                """;
        String created = mvc.perform(post("/api/v1/tenants/acme/changes").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long mixed = JsonPath.parse(created).read("$.version", Long.class);
        double rejectedBefore = registry.counter("config.changes", "kind", "PROMOTE", "result", "rejected").count();

        mvc.perform(post("/api/v1/tenants/acme/changes/" + mixed + "/promote").header("Authorization", bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_A_STAGED_CHANGE"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("the offices disagree on the value for booking.cutoff"));

        assertThat(currentState.snapshot().version()).isEqualTo(mixed);
        assertThat(registry.counter("config.changes", "kind", "PROMOTE", "result", "rejected").count())
                .isEqualTo(rejectedBefore + 1);
    }

    @Test
    void sameStagedVersionCannotBePromotedTwice() throws Exception {
        long staged = JsonPath.parse(stage().andReturn().getResponse().getContentAsString()).read("$.version", Long.class);
        promote(staged).andExpect(status().isCreated());

        promote(staged).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STAGED_CHANGE_SUPERSEDED"));

        assertThat(currentState.snapshot().version()).isEqualTo(staged + 1);
    }

    @Test
    void officeChangedAgainSinceStagingMakesThePromoteAConflictThatWritesNothing() throws Exception {
        long staged = JsonPath.parse(stage("PT45M").andReturn().getResponse().getContentAsString())
                .read("$.version", Long.class);
        long newer = submit("""
                {"reason": "blr wants 50m", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT50M"}]}
                """);
        double rejectedBefore = registry.counter("config.changes", "kind", "PROMOTE", "result", "rejected").count();

        String body = promote(staged).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STAGED_CHANGE_SUPERSEDED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("blr-ecospace", "booking.cutoff").doesNotContain("PT45M", "PT50M");
        assertThat(currentState.snapshot().version()).isEqualTo(newer);
        cutoffAt("blr-ecospace").andExpect(jsonPath("$.value").value("PT50M"));
        cutoffAt("pune-magarpatta").andExpect(jsonPath("$.value").value("PT1H"));
        assertThat(registry.counter("config.changes", "kind", "PROMOTE", "result", "rejected").count())
                .isEqualTo(rejectedBefore + 1);
    }

    @Test
    void onePilotOfficeChangeIsPromotedToEveryOffice() throws Exception {
        long pilot = submit("""
                {"reason": "pilot in blr", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}]}
                """);

        promote(pilot).andExpect(status().isCreated());

        cutoffAt("pune-magarpatta").andExpect(jsonPath("$.value").value("PT30M"))
                .andExpect(jsonPath("$.source").value("CLIENT"));
        cutoffAt("blr-ecospace").andExpect(jsonPath("$.source").value("CLIENT"));
    }

    private ResultActions promote(long version) throws Exception {
        return mvc.perform(post("/api/v1/tenants/acme/changes/" + version + "/promote").header("Authorization", bearer()));
    }

    private long submit(String body) throws Exception {
        String created = mvc.perform(post("/api/v1/tenants/acme/changes").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(created).read("$.version", Long.class);
    }

    private ResultActions stage() throws Exception {
        return stage("PT30M");
    }

    private ResultActions stage(String value) throws Exception {
        String body = """
                {"reason": "try a new cutoff in two offices first", "targetOffices": ["blr-ecospace", "hyd-hitec"],
                 "operations": [{"key": "booking.cutoff", "value": "%s"}]}
                """.formatted(value);
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
