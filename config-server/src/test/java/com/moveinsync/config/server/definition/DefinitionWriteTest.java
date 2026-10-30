package com.moveinsync.config.server.definition;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class DefinitionWriteTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    CurrentState currentState;
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
    void nullRulesLeaveTheRulesUnchanged() throws Exception {
        Rules before = cutoff().rules();

        update("booking.cutoff", "{\"rules\": null, \"description\": \"closes earlier\"}")
                .andExpect(status().isOk());

        assertThat(cutoff().rules()).isEqualTo(before);
        assertThat(cutoff().rules().lessThan()).isEqualTo("booking.maxAdvance");
        assertThat(cutoff().description()).isEqualTo("closes earlier");
    }

    @Test
    void nullDefaultAndDescriptionLeaveThemUnchanged() throws Exception {
        String description = cutoff().description();

        update("booking.cutoff", "{\"default\": null, \"description\": null, \"rules\": {\"min\": \"PT5M\", "
                + "\"max\": \"PT24H\", \"lessThan\": \"booking.maxAdvance\"}}")
                .andExpect(status().isOk());

        assertThat(cutoff().defaultValue()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cutoff().description()).isEqualTo(description);
        assertThat(cutoff().rules().min().asText()).isEqualTo("PT5M");
    }

    @Test
    void durationMinGivenAsANumberIsRefusedAsInvalidRules() throws Exception {
        long version = currentState.snapshot().version();
        double rejectedBefore = rejectedDefinitions();

        define("ride.minDuration", "DURATION", "{\"min\": 5}", "\"PT30M\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RULES"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("rules.min")));

        assertThat(currentState.snapshot().version()).isEqualTo(version);
        assertThat(rejectedDefinitions()).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void numberMinGivenAsTextIsRefusedAndNotReadAsZero() throws Exception {
        double rejectedBefore = rejectedDefinitions();

        define("ride.maxStops", "NUMBER", "{\"min\": \"abc\"}", "3")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RULES"))
                .andExpect(content().string(not(containsString("abc"))));

        assertThat(currentState.snapshot().definitions()).doesNotContainKey("ride.maxStops");
        assertThat(rejectedDefinitions()).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void durationMaxThatIsNotADurationIsRefusedOnUpdate() throws Exception {
        Rules before = cutoff().rules();

        update("booking.cutoff", "{\"rules\": {\"max\": \"1 hour\"}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RULES"))
                .andExpect(content().string(not(containsString("1 hour"))));

        assertThat(cutoff().rules()).isEqualTo(before);
    }

    @Test
    void lessThanNamingAnUnknownSettingIsRefused() throws Exception {
        define("ride.minDuration", "DURATION", "{\"lessThan\": \"ride.nothing\"}", "\"PT30M\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RULES"))
                .andExpect(jsonPath("$.errors[0].message").value("rules.lessThan must name an existing setting"));
    }

    @Test
    void lessThanBetweenADurationAndANumberIsRefused() throws Exception {
        // booking.maxActive is a NUMBER: the order would never be enforced, so it is not accepted
        define("ride.minDuration", "DURATION", "{\"lessThan\": \"booking.maxActive\"}", "\"PT30M\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RULES"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("rules.lessThan must name a setting of the same type, NUMBER or DURATION"));
    }

    @Test
    void unknownTypeErrorNamesTheDefinitionKey() throws Exception {
        define("ride.colour", "COLOUR", "{}", "\"red\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_TYPE"))
                .andExpect(jsonPath("$.errors[0].key").value("ride.colour"));
    }

    @Test
    void defaultOutsideItsOwnRulesNamesTheDefinitionKey() throws Exception {
        define("ride.maxStops", "NUMBER", "{\"min\": 1, \"max\": 5}", "9")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"))
                .andExpect(jsonPath("$.errors[0].key").value("ride.maxStops"));
    }

    @Test
    void badDefaultOnUpdateNamesTheKeyFromThePath() throws Exception {
        update("booking.cutoff", "{\"default\": 30}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].key").value("booking.cutoff"));
    }

    @Test
    void invalidRulesAndExistingKeyErrorsNameTheKeyToo() throws Exception {
        define("ride.minDuration", "DURATION", "{\"min\": 5}", "\"PT30M\"")
                .andExpect(jsonPath("$.errors[0].key").value("ride.minDuration"));
        define("booking.cutoff", "DURATION", "{}", "\"PT30M\"")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("DEFINITION_EXISTS"))
                .andExpect(jsonPath("$.errors[0].key").value("booking.cutoff"));
    }

    private SettingDefinition cutoff() {
        return currentState.snapshot().definitions().get("booking.cutoff");
    }

    private double rejectedDefinitions() {
        return registry.counter("config.changes", "kind", ChangeKind.DEFINITION.name(), "result", "rejected").count();
    }

    private ResultActions define(String key, String type, String rules, String defaultJson) throws Exception {
        String body = "{\"key\": \"" + key + "\", \"type\": \"" + type + "\", \"rules\": " + rules
                + ", \"default\": " + defaultJson + "}";
        return mvc.perform(post("/api/v1/definitions").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions update(String key, String body) throws Exception {
        return mvc.perform(put("/api/v1/definitions/" + key).header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String admin() {
        return "Bearer " + jwt.issue("admin", Role.ADMIN, null);
    }
}
