package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// kept as raw JSON because each type reads min/max differently (a number for NUMBER, "PT1H" for DURATION)
public record Rules(Map<String, JsonNode> raw) {

    public static final Rules NONE = new Rules(Map.of());

    public Rules {
        raw = Map.copyOf(raw);
    }

    public JsonNode min() {
        return raw.get("min");
    }

    public JsonNode max() {
        return raw.get("max");
    }

    public boolean integerOnly() {
        JsonNode node = raw.get("integerOnly");
        return node != null && node.asBoolean();
    }

    public List<String> values() {
        List<String> values = new ArrayList<>();
        JsonNode node = raw.get("values");
        if (node != null) {
            for (JsonNode value : node) {
                values.add(value.asText());
            }
        }
        return values;
    }

    public String itemType() {
        JsonNode node = raw.get("itemType");
        return node == null ? "STRING" : node.asText();
    }

    public int maxItems() {
        JsonNode node = raw.get("maxItems");
        return node == null ? Integer.MAX_VALUE : node.asInt();
    }

    public boolean allowOvernight() {
        JsonNode node = raw.get("allowOvernight");
        return node != null && node.asBoolean();
    }

    // key of the partner setting this one must stay below, or null
    public String lessThan() {
        JsonNode node = raw.get("lessThan");
        return node == null ? null : node.asText();
    }
}
