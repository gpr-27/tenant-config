package com.moveinsync.config.server.change;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.UncheckedIOException;

@Entity
@Table(name = "config_override")
public class OverrideEntity {

    @Embeddable
    public record Id(String tenantId, String scopeLevel, String scopeId, String key) {

        static Id of(String tenantId, ScopeKey scope, String key) {
            return new Id(tenantId, scope.level().name(), scope.id(), key);
        }
    }

    @EmbeddedId
    private Id id;

    @JdbcTypeCode(SqlTypes.JSON)
    private String value;

    private long version;

    protected OverrideEntity() {
    }

    OverrideEntity(Id id, JsonNode value, long version) {
        this.id = id;
        this.value = value.toString();
        this.version = version;
    }

    public String getTenantId() {
        return id.tenantId();
    }

    public ScopeKey getScope() {
        return new ScopeKey(ScopeLevel.valueOf(id.scopeLevel()), id.scopeId());
    }

    public String getKey() {
        return id.key();
    }

    public JsonNode getValue() {
        try {
            return SnapshotCodec.mapper().readTree(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
