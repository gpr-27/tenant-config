package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangePayload;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.DefinitionChanged;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.UncheckedIOException;
import java.time.Instant;

@Entity
@Table(name = "config_change")
public class ChangeEntity {

    // allocationSize 1: the version is the next value of the sequence, never a block reserved ahead
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "config_version")
    @SequenceGenerator(name = "config_version", sequenceName = "config_version_seq", allocationSize = 1)
    private Long version;

    private String tenantId;
    private String author;
    private String reason;
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    private String operations;

    private String idempotencyKey;
    private String requestHash;
    private Instant createdAt;

    protected ChangeEntity() {
    }

    private ChangeEntity(String tenantId, String author, String reason, ChangeKind kind, JsonNode operations,
                         String idempotencyKey, String requestHash, Instant createdAt) {
        this.tenantId = tenantId;
        this.author = author;
        this.reason = reason;
        this.kind = kind.name();
        this.operations = operations.toString();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }

    static ChangeEntity of(ChangeSet changeSet, ChangeKind kind, String idempotencyKey, String requestHash,
                           Instant createdAt) {
        return new ChangeEntity(changeSet.tenantId(), changeSet.author(), changeSet.reason(), kind,
                ChangeEventCodec.operationsToJson(changeSet.operations()), idempotencyKey, requestHash, createdAt);
    }

    static ChangeEntity ofDefinition(SettingDefinition definition, String author, String reason, Instant createdAt) {
        return new ChangeEntity("*", author, reason, ChangeKind.DEFINITION, SnapshotCodec.definitionToJson(definition),
                null, null, createdAt);
    }

    public ChangeEvent toEvent() {
        ChangeKind changeKind = ChangeKind.valueOf(kind);
        ChangePayload payload = changeKind == ChangeKind.DEFINITION
                ? new DefinitionChanged(SnapshotCodec.definitionFromJson(operationsJson()))
                : new OverridesChanged(new ChangeSet(tenantId, author, reason,
                        ChangeEventCodec.operationsFromJson(operationsJson())));
        return new ChangeEvent(version, changeKind, payload, createdAt);
    }

    public JsonNode operationsJson() {
        try {
            return SnapshotCodec.mapper().readTree(operations);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long getVersion() {
        return version;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getAuthor() {
        return author;
    }

    public String getReason() {
        return reason;
    }

    public String getKind() {
        return kind;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
