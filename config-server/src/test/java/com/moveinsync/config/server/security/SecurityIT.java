package com.moveinsync.config.server.security;

import com.jayway.jsonpath.JsonPath;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// observability on: Spring Boot tests switch metric export off, and /actuator/prometheus is checked here
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestDatabase.class)
class SecurityIT {

    private static final String CUTOFF_CHANGE = """
            {"reason": "pilot", "operations": [
              {"op": "SET", "level": "CLIENT", "scopeId": "%s", "key": "booking.cutoff", "value": "PT30M"}]}
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
    }

    @Test
    void loginReturnsRoleAndTenant() throws Exception {
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"acme-ops\", \"password\": \"ops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.tenantId").value("acme"));
    }

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"acme-ops\", \"password\": \"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/tenants/acme/resolve").param("key", "booking.cutoff"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void forgedTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/tenants/acme/resolve").param("key", "booking.cutoff")
                        .header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeCannotPostAChange() throws Exception {
        postChange(login("acme-emp", "emp"), "acme")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
    }

    @Test
    void operatorOfAnotherTenantGets404() throws Exception {
        String acmeOps = login("acme-ops", "ops");

        postChange(acmeOps, "globex")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/tenants/globex/resolve").param("key", "booking.cutoff").header("Authorization", acmeOps))
                .andExpect(status().isNotFound());
    }

    @Test
    void serviceSnapshotSeesBothTenants() throws Exception {
        postChange(login("acme-ops", "ops"), "acme").andExpect(status().isCreated());
        postChange(login("globex-ops", "ops"), "globex").andExpect(status().isCreated());

        String full = snapshot(login("booking-svc", "svc"), null);

        assertThat(JsonPath.parse(full).read("$.overrides[*].tenantId", List.class))
                .containsExactlyInAnyOrder("acme", "globex");
    }

    @Test
    void operatorSnapshotSeesOnlyItsOwnTenant() throws Exception {
        String acmeOps = login("acme-ops", "ops");
        long acme = version(postChange(acmeOps, "acme"));
        long globex = version(postChange(login("globex-ops", "ops"), "globex"));

        String full = snapshot(acmeOps, null);
        assertThat(JsonPath.parse(full).read("$.overrides[*].tenantId", List.class)).containsExactly("acme");

        String delta = snapshot(acmeOps, acme - 1);
        assertThat(JsonPath.parse(delta).read("$.type", String.class)).isEqualTo("delta");
        assertThat(JsonPath.parse(delta).read("$.to", Long.class)).isEqualTo(globex);
        assertThat(JsonPath.parse(delta).read("$.changes[*].version", List.class)).containsExactly((int) acme);
    }

    @Test
    void onlyServicesAndAdminsOpenTheStream() throws Exception {
        mvc.perform(get("/api/v1/config/stream").header("Authorization", login("acme-ops", "ops")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreatesADefinitionAndAnOperatorCannot() throws Exception {
        String definition = """
                {"key": "ride.maxStops", "type": "NUMBER", "rules": {"integerOnly": true, "min": 0, "max": 5},
                 "default": 2, "description": "Maximum pickup stops on one ride"}
                """;

        mvc.perform(post("/api/v1/definitions").header("Authorization", login("acme-ops", "ops"))
                        .contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/definitions").header("Authorization", login("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(definition))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(9));
        mvc.perform(get("/api/v1/definitions").header("Authorization", login("acme-emp", "emp")))
                .andExpect(jsonPath("$[?(@.key == 'ride.maxStops')].default").value(2));
    }

    @Test
    void healthAndPrometheusArePublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        String metrics = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(metrics).contains("config_server_version", "config_push_connections", "config_recent_changes_size",
                "config_changes_total{kind=\"DEFINITION\",result=\"accepted\"}", "config_push_send_failures_total",
                "config_resolve_latency_seconds");
    }

    private ResultActions postChange(String bearer, String tenant) throws Exception {
        return mvc.perform(post("/api/v1/tenants/" + tenant + "/changes").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(CUTOFF_CHANGE.formatted(tenant)));
    }

    private String snapshot(String bearer, Long since) throws Exception {
        var request = get("/api/v1/config/snapshot").header("Authorization", bearer);
        if (since != null) {
            request.param("since", since.toString());
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String login(String username, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.parse(response).read("$.token", String.class);
    }

    private static long version(ResultActions result) throws Exception {
        return JsonPath.parse(result.andReturn().getResponse().getContentAsString()).read("$.version", Long.class);
    }
}
