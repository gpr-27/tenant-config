package com.moveinsync.config.server.change;

import com.jayway.jsonpath.JsonPath;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.security.JwtService;
import com.moveinsync.config.server.security.Role;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class IdempotencyTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
    }

    @Test
    void retryWithSameKeyAndBodyReturnsTheSameVersionAndWritesOneRow() throws Exception {
        long first = version(submit("acme", "retry-1", "PT30M").andExpect(status().isCreated()));
        long second = version(submit("acme", "retry-1", "PT30M").andExpect(status().isCreated()));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from config_change where idempotency_key = 'retry-1'",
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select max(version) from config_change", Long.class)).isEqualTo(first);
    }

    @Test
    void sameKeyWithAnotherBodyIsAConflict() throws Exception {
        submit("acme", "retry-2", "PT30M").andExpect(status().isCreated());

        submit("acme", "retry-2", "PT45M")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void sameKeyInAnotherTenantIsIndependent() throws Exception {
        long acme = version(submit("acme", "retry-3", "PT30M").andExpect(status().isCreated()));
        long globex = version(submit("globex", "retry-3", "PT30M").andExpect(status().isCreated()));

        assertThat(globex).isEqualTo(acme + 1);
    }

    private ResultActions submit(String tenant, String idempotencyKey, String cutoff) throws Exception {
        String body = """
                {"reason": "retry", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "main", "key": "booking.cutoff", "value": "%s"}]}
                """.formatted(cutoff);
        return mvc.perform(post("/api/v1/tenants/" + tenant + "/changes")
                .header("Authorization", "Bearer " + jwt.issue(tenant + "-ops", Role.OPERATOR, tenant))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long version(ResultActions result) throws Exception {
        return JsonPath.parse(result.andReturn().getResponse().getContentAsString()).read("$.version", Long.class);
    }
}
