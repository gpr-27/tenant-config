package com.moveinsync.config.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.change.OverrideRepository;
import com.moveinsync.config.server.security.JwtService;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.state.StateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class ProblemDetailTest {

    @SpyBean
    OverrideRepository overrides;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;
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
    void unexpectedErrorIs500WithRequestIdAndNothingInternal() throws Exception {
        doThrow(new IllegalStateException("boom in com.moveinsync.config.server.SecretThing"))
                .when(overrides).applyAll(any(), any(), anyLong());
        String body = """
                {"reason": "pilot", "operations": [
                  {"op": "SET", "level": "OFFICE", "scopeId": "blr-ecospace", "key": "booking.cutoff", "value": "PT30M"}]}
                """;

        MockHttpServletResponse response = mvc.perform(post("/api/v1/tenants/acme/changes")
                        .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL"))
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andReturn().getResponse();

        String requestId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(response.getContentAsString())
                .contains("\"requestId\":\"" + requestId + "\"")
                .doesNotContain("Exception", "boom", "com.moveinsync", ".java", "trace");
    }

    @Test
    void badSinceParameterIsNamedButNotEchoed() throws Exception {
        String body = mvc.perform(get("/api/v1/tenants/acme/changes").param("since", "abc123")
                        .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid value for 'since'"))
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("abc123");
    }

    @Test
    void badLastEventIdHeaderIsNamedButNotEchoed() throws Exception {
        String body = mvc.perform(get("/api/v1/config/stream").header("Last-Event-ID", "xyz789")
                        .header("Authorization", "Bearer " + jwt.issue("svc", Role.SERVICE, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid value for 'Last-Event-ID'"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("xyz789");
    }

    @Test
    void badScopeLevelInThePathIsNamedButNotEchoed() throws Exception {
        String body = mvc.perform(delete("/api/v1/tenants/acme/overrides/PLANET42/blr-ecospace/booking.cutoff")
                        .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("invalid value for 'level'"))
                .andReturn().getResponse().getContentAsString();

        // `instance` is the request path itself, so only the rest of the body must not repeat the value
        ObjectNode problem = (ObjectNode) objectMapper.readTree(body);
        problem.remove("instance");
        assertThat(problem.toString()).doesNotContain("PLANET42");
    }

    @Test
    void unreadableBodyIsNotEchoed() throws Exception {
        String body = mvc.perform(post("/api/v1/tenants/acme/changes")
                        .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": secret-77, \"operations\": ["))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-77");
    }

    @Test
    void callerSuppliedRequestIdIsEchoed() throws Exception {
        mvc.perform(post("/api/v1/tenants/acme/changes").header(RequestIdFilter.HEADER, "req-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").value("req-123"))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }
}
