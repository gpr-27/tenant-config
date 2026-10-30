package com.moveinsync.config.server.web;

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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class RequestValidationTest {

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
    void missingReasonAndEmptyOperationsAreBothNamedWithTheirConstraint() throws Exception {
        postChange("{\"operations\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("reason", "operations")))
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("NotBlank", "NotEmpty")));
    }

    @Test
    void blankKeyInsideAnOperationIsNamedByItsPathButNotEchoed() throws Exception {
        String body = postChange("""
                {"reason": "pilot", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "  ", "value": "secret-41"}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("operations[0].key"))
                .andExpect(jsonPath("$.errors[0].code").value("NotBlank"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-41", "rejectedValue");
    }

    @Test
    void reasonLongerThan500CharactersIsRefused() throws Exception {
        String reason = "x".repeat(501);

        String body = postChange("""
                {"reason": "%s", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}]}
                """.formatted(reason))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("Size"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(reason);
    }

    @Test
    void reasonOfExactly500CharactersIsAccepted() throws Exception {
        postChange("""
                {"reason": "%s", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}]}
                """.formatted("x".repeat(500)))
                .andExpect(status().isCreated());
    }

    @Test
    void historyLimitOfZeroOrBelowIsRefusedNotQuietlyRaisedToOne() throws Exception {
        history("0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].code").value("Min"));
        history("-5").andExpect(status().isBadRequest());
    }

    @Test
    void historyLimitAbove1000IsRefused() throws Exception {
        history("1001").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"))
                .andExpect(jsonPath("$.errors[0].code").value("Max"));
        history("1000").andExpect(status().isOk());
    }

    private ResultActions postChange(String body) throws Exception {
        return mvc.perform(post("/api/v1/tenants/acme/changes").header("Authorization", operator())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions history(String limit) throws Exception {
        return mvc.perform(get("/api/v1/tenants/acme/changes").param("limit", limit).header("Authorization", operator()));
    }

    private String operator() {
        return "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme");
    }
}
