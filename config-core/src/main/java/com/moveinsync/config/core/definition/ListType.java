package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.moveinsync.config.core.error.InvalidValueException;

import java.util.ArrayList;
import java.util.List;

public final class ListType implements SettingType {

    ListType() {
    }

    @Override
    public String name() {
        return "LIST";
    }

    @Override
    public Object parse(JsonNode raw) {
        if (raw == null || !raw.isArray()) {
            throw mismatch();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isTextual()) {
                throw mismatch();
            }
            items.add(item.asText());
        }
        return List.copyOf(items);
    }

    @Override
    public void validate(Object value, Rules rules) {
        if (!(value instanceof List<?> items)) {
            throw mismatch();
        }
        if (items.size() > rules.maxItems()) {
            throw new InvalidValueException("TOO_MANY_ITEMS", "at most " + rules.maxItems() + " items allowed");
        }
        if (rules.itemType().equals("ENUM")) {
            List<String> allowed = rules.values();
            for (Object item : items) {
                if (!allowed.contains(item)) {
                    throw new InvalidValueException("NOT_IN_ENUM", "every item must be one of " + allowed);
                }
            }
        }
    }

    @Override
    public JsonNode toJson(Object value) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (Object item : (List<?>) value) {
            array.add((String) item);
        }
        return array;
    }

    private static InvalidValueException mismatch() {
        return new InvalidValueException("TYPE_MISMATCH", "expected an array of strings");
    }
}
