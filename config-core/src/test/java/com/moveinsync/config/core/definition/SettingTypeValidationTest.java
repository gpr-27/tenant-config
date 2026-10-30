package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.error.InvalidValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingTypeValidationTest {

    // an empty code column means the value is accepted
    @ParameterizedTest(name = "{0} = {1} -> {2}")
    @CsvSource(delimiter = '|', textBlock = """
            booking.maxActive  | 3                                                  |
            booking.maxActive  | 1                                                  |
            booking.maxActive  | 10                                                 |
            booking.maxActive  | 3.0                                                |
            booking.maxActive  | "3"                                                | TYPE_MISMATCH
            booking.maxActive  | 0                                                  | OUT_OF_RANGE
            booking.maxActive  | 11                                                 | OUT_OF_RANGE
            booking.maxActive  | 2.5                                                | TYPE_MISMATCH
            booking.cutoff     | "PT30M"                                            |
            booking.cutoff     | "PT0M"                                             |
            booking.cutoff     | "PT24H"                                            |
            booking.cutoff     | "-PT5M"                                            | NEGATIVE_DURATION
            booking.cutoff     | "PT25H"                                            | OUT_OF_RANGE
            booking.maxAdvance | "PT30M"                                            | OUT_OF_RANGE
            booking.cutoff     | "30 minutes"                                       | TYPE_MISMATCH
            booking.cutoff     | 30                                                 | TYPE_MISMATCH
            office.hours       | {"start":"08:00","end":"20:00"}                    |
            office.hours       | {"start":"20:00","end":"08:00"}                    | WINDOW_END_BEFORE_START
            office.hours       | {"start":"09:00","end":"09:00"}                    | WINDOW_EMPTY
            office.hours       | {"start":"22:00","end":"06:00","overnight":true}   | OVERNIGHT_NOT_ALLOWED
            night.window       | {"start":"22:00","end":"06:00","overnight":true}   |
            night.window       | {"start":"22:00","end":"06:00"}                    | WINDOW_END_BEFORE_START
            night.window       | {"start":"22:00","end":"22:00","overnight":true}   | WINDOW_EMPTY
            night.window       | {"start":"20:00","end":"23:00","overnight":true}   | OVERNIGHT_FLAG_MISMATCH
            night.window       | {"start":"25:00","end":"06:00"}                    | TYPE_MISMATCH
            night.window       | {"start":"22:00","end":"06:00","overnight":"yes"}  | TYPE_MISMATCH
            night.window       | "22:00-06:00"                                      | TYPE_MISMATCH
            escort.required    | "true"                                             |
            escort.required    | "maybe"                                            | NOT_IN_ENUM
            escort.required    | true                                               | TYPE_MISMATCH
            cab.types          | ["SEDAN","SUV"]                                    |
            cab.types          | ["SEDAN","SUV","TEMPO"]                            |
            cab.types          | []                                                 |
            cab.types          | ["SEDAN","SUV","TEMPO","SEDAN"]                    | TOO_MANY_ITEMS
            cab.types          | ["SEDAN","BUS"]                                    | NOT_IN_ENUM
            cab.types          | "SEDAN"                                            | TYPE_MISMATCH
            cab.types          | [1]                                                | TYPE_MISMATCH
            """)
    void everyAcceptAndRejectRow(String key, String json, String expectedCode) {
        assertThat(codeFor(key, json)).isEqualTo(expectedCode);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', textBlock = """
            booking.maxActive | 3
            booking.cutoff    | "PT30M"
            night.window      | {"start":"22:00","end":"06:00","overnight":true}
            office.hours      | {"start":"08:00","end":"20:00","overnight":false}
            escort.required   | "true"
            cab.types         | ["SEDAN","TEMPO"]
            """)
    void acceptedValueSurvivesToJsonAndBack(String key, String json) {
        SettingType type = Seeds.definition(key).type();
        Object value = type.parse(Seeds.json(json));

        assertThat(type.parse(type.toJson(value))).isEqualTo(value);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"10", "10.0", "1E+1", "10.000"})
    void equalNumbersParseToOneForm(String json) {
        SettingType type = SettingTypes.NUMBER;

        assertThat(type.parse(Seeds.json(json))).isEqualTo(new java.math.BigDecimal("10"));
        assertThat(type.toJson(type.parse(Seeds.json(json))).toString()).isEqualTo("10");
    }

    @Test
    void timeWindowOvernightDefaultsToFalse() {
        Object value = SettingTypes.TIME_WINDOW.parse(Seeds.json("{\"start\":\"08:00\",\"end\":\"20:00\"}"));

        assertThat(value).isEqualTo(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(20, 0), false));
    }

    @Test
    void timeWindowIsWrittenAsStartEndOvernight() {
        JsonNode json = SettingTypes.TIME_WINDOW.toJson(new TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0), true));

        assertThat(json).isEqualTo(Seeds.json("{\"start\":\"22:00\",\"end\":\"06:00\",\"overnight\":true}"));
    }

    @Test
    void unknownTypeNameIsRejected() {
        assertThatThrownBy(() -> SettingTypes.of("COLOUR"))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).errorCode())
                .isEqualTo("UNKNOWN_TYPE");
    }

    @Test
    void definitionRejectsAnInvalidDefault() {
        Rules rules = Seeds.definition("booking.cutoff").rules();

        assertThatThrownBy(() -> new SettingDefinition("booking.cutoff", SettingTypes.DURATION, rules,
                Duration.ofHours(30), "too long"))
                .isInstanceOf(InvalidValueException.class)
                .extracting(e -> ((InvalidValueException) e).errorCode())
                .isEqualTo("OUT_OF_RANGE");
    }

    private static String codeFor(String key, String json) {
        SettingDefinition definition = Seeds.definition(key);
        try {
            Object value = definition.type().parse(Seeds.json(json));
            definition.type().validate(value, definition.rules());
            return null;
        } catch (InvalidValueException e) {
            return e.errorCode();
        }
    }
}
