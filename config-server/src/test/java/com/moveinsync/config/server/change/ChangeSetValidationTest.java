package com.moveinsync.config.server.change;

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

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ChangeSetValidationTest {

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
    void rejectionIsAProblemDetailWithOneErrorPerOperation() throws Exception {
        submit("""
                {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT-30M"},
                {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.maxActive", "value": 50}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].operationIndex").value(0))
                .andExpect(jsonPath("$.errors[0].key").value("booking.cutoff"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.errors[1].operationIndex").value(1))
                .andExpect(jsonPath("$.errors[1].code").value("OUT_OF_RANGE"));
    }

    @Test
    void negativeDurationIsRejectedWithoutEchoingTheValue() throws Exception {
        submit("""
                {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT-30M"}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("NEGATIVE_DURATION"))
                .andExpect(content().string(not(containsString("PT-30M"))));
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        submit("""
                {"op": "SET", "level": "CLIENT", "scopeId": "acme", "key": "booking.colour", "value": "red"}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].key").value("booking.colour"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_KEY"));
    }

    @Test
    void windowEndingBeforeItStartsIsRejected() throws Exception {
        submit("""
                {"op": "SET", "level": "CLIENT", "scopeId": "acme", "key": "office.hours",
                 "value": {"start": "20:00", "end": "08:00"}}
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("WINDOW_END_BEFORE_START"));
    }

    @Test
    void malformedBodyIsA400ProblemDetail() throws Exception {
        submit("""
                {"op": "MAYBE", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private ResultActions submit(String operations) throws Exception {
        String body = "{\"reason\": \"test\", \"operations\": [" + operations + "]}";
        return mvc.perform(post("/api/v1/tenants/acme/changes")
                .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
