package com.moveinsync.config.server.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.error.InvalidValueException;
import com.moveinsync.config.core.error.UnknownSettingException;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.change.Rejections;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.web.dto.DefinitionRequest;
import com.moveinsync.config.server.web.dto.DefinitionUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefinitionService {

    private final ChangeSetService changeSets;
    private final CurrentState currentState;
    private final Rejections rejections;

    public List<SettingDefinition> list() {
        List<SettingDefinition> definitions = new ArrayList<>(currentState.snapshot().definitions().values());
        definitions.sort(Comparator.comparing(SettingDefinition::key));
        return definitions;
    }

    // read and submit under one hold of the write lock, so two concurrent edits cannot both start from the
    // same definition and have the second silently undo the first
    public long create(DefinitionRequest request, String author) {
        return changeSets.underWriteLock(() -> {
            Map<String, SettingDefinition> existing = currentState.snapshot().definitions();
            if (existing.containsKey(request.key())) {
                throw refused(List.of(new ValidationError(0, request.key(), "DEFINITION_EXISTS",
                        "a setting with this key is already defined; change it with PUT")));
            }
            ObjectNode json = SnapshotCodec.mapper().createObjectNode();
            json.put("key", request.key());
            json.put("type", request.type());
            json.set("rules", given(request.rules()) ? request.rules() : json.objectNode());
            json.set("default", request.defaultValue());
            json.put("description", request.description() == null ? "" : request.description());
            return changeSets.submitDefinition(parse(json, existing), author, "define " + request.key());
        });
    }

    // a new default is how a GLOBAL value changes; the type of a key never changes
    public long update(String key, DefinitionUpdate request, String author) {
        return changeSets.underWriteLock(() -> {
            Map<String, SettingDefinition> existing = currentState.snapshot().definitions();
            SettingDefinition current = existing.get(key);
            if (current == null) {
                throw new UnknownSettingException(key);
            }
            ObjectNode json = SnapshotCodec.definitionToJson(current);
            if (given(request.rules())) {
                json.set("rules", request.rules());
            }
            if (given(request.defaultValue())) {
                json.set("default", request.defaultValue());
            }
            if (request.description() != null) {
                json.put("description", request.description());
            }
            return changeSets.submitDefinition(parse(json, existing), author, "update " + key);
        });
    }

    // Jackson hands a JsonNode field an explicit null as NullNode, not as Java null; both mean "not given"
    private static boolean given(JsonNode node) {
        return node != null && !node.isNull();
    }

    // malformed rules, an unknown type or a default its own rules reject is refused before the write path
    private SettingDefinition parse(ObjectNode json, Map<String, SettingDefinition> existing) {
        String key = json.path("key").asText();
        Map<String, String> typeByKey = new HashMap<>();
        for (SettingDefinition definition : existing.values()) {
            typeByKey.put(definition.key(), definition.type().name());
        }
        List<ValidationError> errors = RulesShape.check(key, json.path("type").asText(), json.get("rules"), typeByKey);
        if (!errors.isEmpty()) {
            throw refused(errors);
        }
        try {
            return SnapshotCodec.definitionFromJson(json);
        } catch (InvalidValueException e) {
            // rethrown as a ValidationException so the 422's errors[] names the key, which core's exception lacks
            throw refused(List.of(new ValidationError(0, key, e.errorCode(), e.getMessage())));
        }
    }

    private ValidationException refused(List<ValidationError> errors) {
        rejections.invalid(ChangeKind.DEFINITION, "*", errors);
        return new ValidationException(errors);
    }

    // through the normal write path, so the seeded definitions get versions 1..8 like any other change
    public void seed() {
        try (InputStream in = new ClassPathResource("seed-definitions.json").getInputStream()) {
            for (JsonNode node : SnapshotCodec.mapper().readTree(in)) {
                changeSets.submitDefinition(SnapshotCodec.definitionFromJson(node), "system", "seed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
