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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ChangeLogTest {

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
    void authorIsTheTokenSubjectAndReasonIsStored() throws Exception {
        long version = submit("pilot", "PT30M");

        Map<String, Object> row = jdbc.queryForMap(
                "select tenant_id, author, reason, kind from config_change where version = ?", version);
        assertThat(row).containsEntry("tenant_id", "acme")
                .containsEntry("author", "acme-ops")
                .containsEntry("reason", "pilot")
                .containsEntry("kind", "CHANGE");
    }

    @Test
    void versionsStrictlyIncrease() throws Exception {
        long first = submit("one", "PT30M");
        long second = submit("two", "PT40M");
        long third = submit("three", "PT50M");

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
        List<Long> logged = jdbc.queryForList("select version from config_change order by created_at, version", Long.class);
        assertThat(logged).isSorted().doesNotHaveDuplicates().endsWith(first, second, third);
    }

    @Test
    void auditListShowsTheTenantsChangesInOrder() throws Exception {
        long first = submit("one", "PT30M");
        long second = submit("two", "PT40M");

        mvc.perform(get("/api/v1/tenants/acme/changes").param("since", String.valueOf(first - 1))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(first))
                .andExpect(jsonPath("$[1].version").value(second))
                .andExpect(jsonPath("$[1].author").value("acme-ops"))
                .andExpect(jsonPath("$[1].operations[0].value").value("PT40M"));
    }

    private long submit(String reason, String cutoff) throws Exception {
        String body = """
                {"reason": "%s", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "%s"}]}
                """.formatted(reason, cutoff);
        String response = mvc.perform(post("/api/v1/tenants/acme/changes").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(response).read("$.version", Long.class);
    }

    private String bearer() {
        return "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme");
    }
}
