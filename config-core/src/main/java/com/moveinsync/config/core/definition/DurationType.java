package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.error.InvalidValueException;

import java.time.Duration;
import java.time.format.DateTimeParseException;

public final class DurationType implements SettingType {

    DurationType() {
    }

    @Override
    public String name() {
        return "DURATION";
    }

    @Override
    public Object parse(JsonNode raw) {
        if (raw == null || !raw.isTextual()) {
            throw mismatch();
        }
        try {
            return Duration.parse(raw.asText());
        } catch (DateTimeParseException e) {
            throw mismatch();
        }
    }

    @Override
    public void validate(Object value, Rules rules) {
        if (!(value instanceof Duration duration)) {
            throw mismatch();
        }
        if (duration.isNegative()) {
            throw new InvalidValueException("NEGATIVE_DURATION", "must not be negative");
        }
        if (rules.min() != null && duration.compareTo(Duration.parse(rules.min().asText())) < 0) {
            throw new InvalidValueException("OUT_OF_RANGE", "must be at least " + rules.min().asText());
        }
        if (rules.max() != null && duration.compareTo(Duration.parse(rules.max().asText())) > 0) {
            throw new InvalidValueException("OUT_OF_RANGE", "must be at most " + rules.max().asText());
        }
    }

    @Override
    public JsonNode toJson(Object value) {
        return TextNode.valueOf(value.toString());
    }

    private static InvalidValueException mismatch() {
        return new InvalidValueException("TYPE_MISMATCH", "expected an ISO-8601 duration such as PT30M");
    }
}
