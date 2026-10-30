package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface SettingType permits NumberType, DurationType, TimeWindowType, EnumType, ListType {

    // NUMBER | DURATION | TIME_WINDOW | ENUM | LIST
    String name();

    // throws InvalidValueException(TYPE_MISMATCH) when the JSON has the wrong shape
    Object parse(JsonNode raw);

    // throws InvalidValueException with the rule's error code
    void validate(Object value, Rules rules);

    JsonNode toJson(Object value);
}
