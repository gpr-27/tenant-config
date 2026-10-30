package com.moveinsync.config.core.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.snapshot.SnapshotCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// the spec fixes the SSE frame (data: {ChangeEvent}) but not its JSON; it lives here so server and client share one shape
public final class ChangeEventCodec {

    private ChangeEventCodec() {
    }

    public static ObjectNode toJson(ChangeEvent event) {
        ObjectNode node = SnapshotCodec.mapper().createObjectNode();
        node.put("version", event.version());
        node.put("kind", event.kind().name());
        node.put("createdAt", event.createdAt().toString());
        ObjectNode payload = node.putObject("payload");
        switch (event.payload()) {
            case OverridesChanged changed -> {
                ChangeSet changeSet = changed.changeSet();
                payload.put("type", "OVERRIDES");
                payload.put("tenantId", changeSet.tenantId());
                payload.put("author", changeSet.author());
                payload.put("reason", changeSet.reason());
                payload.set("operations", operationsToJson(changeSet.operations()));
            }
            case DefinitionChanged changed -> {
                payload.put("type", "DEFINITION");
                payload.set("definition", SnapshotCodec.definitionToJson(changed.definition()));
            }
        }
        return node;
    }

    public static ChangeEvent fromJson(JsonNode node) {
        JsonNode payload = node.path("payload");
        ChangePayload decoded = switch (payload.path("type").asText()) {
            case "OVERRIDES" -> new OverridesChanged(new ChangeSet(payload.path("tenantId").asText(),
                    payload.path("author").asText(), payload.path("reason").asText(),
                    operationsFromJson(payload.path("operations"))));
            case "DEFINITION" -> new DefinitionChanged(SnapshotCodec.definitionFromJson(payload.path("definition")));
            default -> throw new IllegalArgumentException("unknown change payload type: " + payload.path("type"));
        };
        return new ChangeEvent(node.path("version").asLong(), ChangeKind.valueOf(node.path("kind").asText()),
                decoded, Instant.parse(node.path("createdAt").asText()));
    }

    // [{op, level, scopeId, key, value}] - the same shape as config_change.operations
    public static ArrayNode operationsToJson(List<Operation> operations) {
        ArrayNode array = SnapshotCodec.mapper().createArrayNode();
        for (Operation op : operations) {
            ObjectNode node = array.addObject();
            node.put("op", op.op().name());
            node.put("level", op.scope().level().name());
            node.put("scopeId", op.scope().id());
            node.put("key", op.key());
            if (op.value() != null) {
                node.set("value", op.value());
            }
        }
        return array;
    }

    public static List<Operation> operationsFromJson(JsonNode array) {
        List<Operation> operations = new ArrayList<>();
        for (JsonNode node : array) {
            ScopeKey scope = new ScopeKey(ScopeLevel.valueOf(node.path("level").asText()), node.path("scopeId").asText());
            JsonNode value = node.get("value");
            operations.add(new Operation(OpKind.valueOf(node.path("op").asText()), scope, node.path("key").asText(),
                    value == null || value.isNull() ? null : value));
        }
        return operations;
    }
}
