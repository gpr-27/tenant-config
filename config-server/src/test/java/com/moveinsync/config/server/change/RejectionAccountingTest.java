package com.moveinsync.config.server.change;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.server.DatabaseReset;
import com.moveinsync.config.server.TestDatabase;
import com.moveinsync.config.server.security.JwtService;
import com.moveinsync.config.server.security.Role;
import com.moveinsync.config.server.state.StateLoader;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// every refused write, wherever it is refused, shows up once in the WARN log and once in config_changes_total
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
@ExtendWith(OutputCaptureExtension.class)
class RejectionAccountingTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    ChangeSetService changeSets;
    @Autowired
    MeterRegistry registry;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StateLoader stateLoader;

    @BeforeEach
    void cleanState() {
        DatabaseReset.reset(jdbc, stateLoader);
    }

    @Test
    void definingAnExistingKeyIsLoggedAndCountedAsRejected(CapturedOutput output) throws Exception {
        double before = rejected(ChangeKind.DEFINITION);
        double codeBefore = validationErrors("DEFINITION_EXISTS");

        define("booking.cutoff", "DURATION", "\"PT30M\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("DEFINITION_EXISTS"));

        assertThat(rejected(ChangeKind.DEFINITION)).isEqualTo(before + 1);
        assertThat(validationErrors("DEFINITION_EXISTS")).isEqualTo(codeBefore + 1);
        oneRejectionWarning(output, "DEFINITION_EXISTS");
    }

    @Test
    void definitionWithAnUnknownTypeIsLoggedAndCountedAsRejected(CapturedOutput output) throws Exception {
        double before = rejected(ChangeKind.DEFINITION);

        define("ride.colour", "COLOUR", "\"red\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_TYPE"));

        assertThat(rejected(ChangeKind.DEFINITION)).isEqualTo(before + 1);
        assertThat(validationErrors("UNKNOWN_TYPE")).isGreaterThanOrEqualTo(1);
        oneRejectionWarning(output, "UNKNOWN_TYPE");
    }

    @Test
    void promotingAChangeThatWasNotStagedIsLoggedAndCountedAsRejected(CapturedOutput output) throws Exception {
        long clientChange = submit("acme", ScopeKey.client("acme"), "PT30M", null);
        double before = rejected(ChangeKind.PROMOTE);

        mvc.perform(post("/api/v1/tenants/acme/changes/" + clientChange + "/promote")
                        .header("Authorization", "Bearer " + jwt.issue("acme-ops", Role.OPERATOR, "acme")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_A_STAGED_CHANGE"));

        assertThat(rejected(ChangeKind.PROMOTE)).isEqualTo(before + 1);
        oneRejectionWarning(output, "NOT_A_STAGED_CHANGE");
    }

    @Test
    void reusedIdempotencyKeyWithAnotherBodyIsLoggedAndCountedAsRejected(CapturedOutput output) {
        submit("acme", ScopeKey.office("blr-ecospace"), "PT30M", "retry-1");
        double before = rejected(ChangeKind.CHANGE);

        assertThatThrownBy(() -> submit("acme", ScopeKey.office("blr-ecospace"), "PT45M", "retry-1"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(rejected(ChangeKind.CHANGE)).isEqualTo(before + 1);
        assertThat(oneRejectionWarning(output, "IDEMPOTENCY_CONFLICT")).doesNotContain("PT45M");
    }

    private double rejected(ChangeKind kind) {
        return registry.counter("config.changes", "kind", kind.name(), "result", "rejected").count();
    }

    private double validationErrors(String code) {
        return registry.counter("config.validation.errors", "code", code).count();
    }

    private static String oneRejectionWarning(CapturedOutput output, String code) {
        List<String> warnings = output.getOut().lines().filter(line -> line.contains("change set rejected")).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("\"level\":\"WARN\"", code);
        return warnings.get(0);
    }

    private long submit(String tenant, ScopeKey scope, String cutoff, String idempotencyKey) {
        Operation op = new Operation(OpKind.SET, scope, "booking.cutoff", TextNode.valueOf(cutoff));
        ChangeSet changeSet = new ChangeSet(tenant, tenant + "-ops", "test", List.of(op));
        return changeSets.submit(changeSet, ChangeKind.CHANGE, idempotencyKey);
    }

    private ResultActions define(String key, String type, String defaultJson) throws Exception {
        String body = "{\"key\": \"" + key + "\", \"type\": \"" + type + "\", \"default\": " + defaultJson + "}";
        return mvc.perform(post("/api/v1/definitions")
                .header("Authorization", "Bearer " + jwt.issue("admin", Role.ADMIN, null))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
