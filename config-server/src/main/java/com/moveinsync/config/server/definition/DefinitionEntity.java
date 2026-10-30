package com.moveinsync.config.server.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.UncheckedIOException;

@Entity
@Table(name = "setting_definition")
public class DefinitionEntity {

    @Id
    private String key;

    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    private String rules;

    @JdbcTypeCode(SqlTypes.JSON)
    private String defaultValue;

    private String description;

    // the change-log version that last wrote this definition
    private long version;

    protected DefinitionEntity() {
    }

    public static DefinitionEntity from(SettingDefinition definition, long version) {
        ObjectNode json = SnapshotCodec.definitionToJson(definition);
        DefinitionEntity entity = new DefinitionEntity();
        entity.key = definition.key();
        entity.type = definition.type().name();
        entity.rules = json.get("rules").toString();
        entity.defaultValue = json.get("default").toString();
        entity.description = definition.description();
        entity.version = version;
        return entity;
    }

    public SettingDefinition toDefinition() {
        try {
            ObjectNode json = SnapshotCodec.mapper().createObjectNode();
            json.put("key", key);
            json.put("type", type);
            json.set("rules", SnapshotCodec.mapper().readTree(rules));
            json.set("default", SnapshotCodec.mapper().readTree(defaultValue));
            json.put("description", description);
            return SnapshotCodec.definitionFromJson(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
