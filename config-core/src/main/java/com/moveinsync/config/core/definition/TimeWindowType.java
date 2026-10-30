package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.core.error.InvalidValueException;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

public final class TimeWindowType implements SettingType {

    TimeWindowType() {
    }

    @Override
    public String name() {
        return "TIME_WINDOW";
    }

    @Override
    public Object parse(JsonNode raw) {
        if (raw == null || !raw.path("start").isTextual() || !raw.path("end").isTextual()) {
            throw mismatch();
        }
        JsonNode overnight = raw.path("overnight");
        if (!overnight.isMissingNode() && !overnight.isBoolean()) {
            throw mismatch();
        }
        try {
            LocalTime start = LocalTime.parse(raw.get("start").asText());
            LocalTime end = LocalTime.parse(raw.get("end").asText());
            return new TimeWindow(start, end, overnight.asBoolean(false));
        } catch (DateTimeParseException e) {
            throw mismatch();
        }
    }

    @Override
    public void validate(Object value, Rules rules) {
        if (!(value instanceof TimeWindow window)) {
            throw mismatch();
        }
        if (window.start().equals(window.end())) {
            throw new InvalidValueException("WINDOW_EMPTY", "start and end must differ");
        }
        if (!window.overnight() && window.end().isBefore(window.start())) {
            throw new InvalidValueException("WINDOW_END_BEFORE_START",
                    "end must be after start unless the window is marked overnight");
        }
        if (window.overnight() && !rules.allowOvernight()) {
            throw new InvalidValueException("OVERNIGHT_NOT_ALLOWED", "this setting does not allow overnight windows");
        }
        if (window.overnight() && window.end().isAfter(window.start())) {
            throw new InvalidValueException("OVERNIGHT_FLAG_MISMATCH",
                    "an overnight window must end before it starts");
        }
    }

    @Override
    public JsonNode toJson(Object value) {
        TimeWindow window = (TimeWindow) value;
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("start", window.start().toString());
        node.put("end", window.end().toString());
        node.put("overnight", window.overnight());
        return node;
    }

    private static InvalidValueException mismatch() {
        return new InvalidValueException("TYPE_MISMATCH",
                "expected {\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"overnight\":true|false}");
    }
}
