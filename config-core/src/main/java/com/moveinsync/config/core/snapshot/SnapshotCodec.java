package com.moveinsync.config.core.snapshot;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingType;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.error.UnknownSettingException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;

import java.util.HashMap;
import java.util.Map;

public final class SnapshotCodec {

    // ISO strings for java.time values; BigDecimal for fractions so NUMBER settings keep their exact value
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private SnapshotCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode toJson(Snapshot snapshot) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", snapshot.version());
        ArrayNode definitions = root.putArray("definitions");
        for (SettingDefinition definition : snapshot.definitions().values()) {
            definitions.add(definitionToJson(definition));
        }

        ArrayNode overrides = root.putArray("overrides");
        for (String tenantId : snapshot.tenantIds()) {
            for (var scope : snapshot.tenantOverrides(tenantId).entrySet()) {
                for (var entry : scope.getValue().entrySet()) {
                    SettingType type = snapshot.definitions().get(entry.getKey()).type();
                    ObjectNode node = overrides.addObject();
                    node.put("tenantId", tenantId);
                    node.put("level", scope.getKey().level().name());
                    node.put("scopeId", scope.getKey().id());
                    node.put("key", entry.getKey());
                    node.set("value", type.toJson(entry.getValue()));
                }
            }
        }
        return root;
    }

    public static Snapshot fromJson(JsonNode json) {
        Map<String, SettingDefinition> definitions = new HashMap<>();
        for (JsonNode node : json.path("definitions")) {
            SettingDefinition definition = definitionFromJson(node);
            definitions.put(definition.key(), definition);
        }

        Map<String, Map<ScopeKey, Map<String, Object>>> overrides = new HashMap<>();
        for (JsonNode node : json.path("overrides")) {
            String key = node.path("key").asText();
            SettingDefinition definition = definitions.get(key);
            if (definition == null) {
                throw new UnknownSettingException(key);
            }
            ScopeKey scope = new ScopeKey(ScopeLevel.valueOf(node.path("level").asText()), node.path("scopeId").asText());
            overrides.computeIfAbsent(node.path("tenantId").asText(), tenant -> new HashMap<>())
                    .computeIfAbsent(scope, s -> new HashMap<>())
                    .put(key, definition.type().parse(node.get("value")));
        }
        return Snapshot.of(json.path("version").asLong(), definitions, overrides);
    }

    public static ObjectNode definitionToJson(SettingDefinition definition) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("key", definition.key());
        node.put("type", definition.type().name());
        node.putObject("rules").setAll(definition.rules().raw());
        node.set("default", definition.type().toJson(definition.defaultValue()));
        node.put("description", definition.description());
        return node;
    }

    public static SettingDefinition definitionFromJson(JsonNode node) {
        SettingType type = SettingTypes.of(node.path("type").asText());
        Map<String, JsonNode> rules = new HashMap<>();
        for (var rule : node.path("rules").properties()) {
            rules.put(rule.getKey(), rule.getValue());
        }
        return new SettingDefinition(node.path("key").asText(), type, new Rules(rules),
                type.parse(node.get("default")), node.path("description").asText(""));
    }
}
