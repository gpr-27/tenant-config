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
import java.util.List;

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

    public long create(DefinitionRequest request, String author) {
        if (currentState.snapshot().definitions().containsKey(request.key())) {
            List<ValidationError> errors = List.of(new ValidationError(0, request.key(), "DEFINITION_EXISTS",
                    "a setting with this key is already defined; change it with PUT"));
            rejections.invalid(ChangeKind.DEFINITION, "*", errors);
            throw new ValidationException(errors);
        }
        ObjectNode json = SnapshotCodec.mapper().createObjectNode();
        json.put("key", request.key());
        json.put("type", request.type());
        json.set("rules", request.rules() == null ? json.objectNode() : request.rules());
        json.set("default", request.defaultValue());
        json.put("description", request.description() == null ? "" : request.description());
        return changeSets.submitDefinition(parse(json), author, "define " + request.key());
    }

    // a new default is how a GLOBAL value changes; the type of a key never changes
    public long update(String key, DefinitionUpdate request, String author) {
        SettingDefinition current = currentState.snapshot().definitions().get(key);
        if (current == null) {
            throw new UnknownSettingException(key);
        }
        ObjectNode json = SnapshotCodec.definitionToJson(current);
        if (request.rules() != null) {
            json.set("rules", request.rules());
        }
        if (request.defaultValue() != null) {
            json.set("default", request.defaultValue());
        }
        if (request.description() != null) {
            json.put("description", request.description());
        }
        return changeSets.submitDefinition(parse(json), author, "update " + key);
    }

    // an unknown type or a default its own rules reject is refused before it reaches the write path
    private SettingDefinition parse(ObjectNode json) {
        try {
            return SnapshotCodec.definitionFromJson(json);
        } catch (InvalidValueException e) {
            rejections.invalid(ChangeKind.DEFINITION, "*",
                    List.of(new ValidationError(0, json.path("key").asText(), e.errorCode(), e.getMessage())));
            throw e;
        }
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
