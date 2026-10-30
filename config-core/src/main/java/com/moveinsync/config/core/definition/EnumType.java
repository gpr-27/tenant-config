package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.error.InvalidValueException;

public final class EnumType implements SettingType {

    EnumType() {
    }

    @Override
    public String name() {
        return "ENUM";
    }

    @Override
    public Object parse(JsonNode raw) {
        if (raw == null || !raw.isTextual()) {
            throw new InvalidValueException("TYPE_MISMATCH", "expected a string");
        }
        return raw.asText();
    }

    @Override
    public void validate(Object value, Rules rules) {
        if (!(value instanceof String text)) {
            throw new InvalidValueException("TYPE_MISMATCH", "expected a string");
        }
        if (!rules.values().contains(text)) {
            throw new InvalidValueException("NOT_IN_ENUM", "must be one of " + rules.values());
        }
    }

    @Override
    public JsonNode toJson(Object value) {
        return TextNode.valueOf((String) value);
    }
}
