package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.DefinitionChanged;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.error.UnknownSettingException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.scope.ScopePath;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// immutable: a change builds a new Snapshot and the holder swaps one reference, so readers never see half a change
public final class Snapshot {

    private static final Snapshot EMPTY = new Snapshot(0, Map.of(), Map.of());

    private final long version;
    private final Map<String, SettingDefinition> definitions;
    // tenant -> scope -> key -> parsed value
    private final Map<String, Map<ScopeKey, Map<String, Object>>> overrides;

    private Snapshot(long version, Map<String, SettingDefinition> definitions,
                     Map<String, Map<ScopeKey, Map<String, Object>>> overrides) {
        this.version = version;
        this.definitions = definitions;
        this.overrides = overrides;
    }

    public static Snapshot empty() {
        return EMPTY;
    }

    // for loaders (codec, server start-up): copies every level so the caller keeps no mutable handle
    public static Snapshot of(long version, Map<String, SettingDefinition> definitions,
                              Map<String, Map<ScopeKey, Map<String, Object>>> overrides) {
        Map<String, Map<ScopeKey, Map<String, Object>>> tenants = new HashMap<>();
        for (var tenant : overrides.entrySet()) {
            Map<ScopeKey, Map<String, Object>> scopes = new HashMap<>();
            for (var scope : tenant.getValue().entrySet()) {
                if (!scope.getValue().isEmpty()) {
                    scopes.put(scope.getKey(), Map.copyOf(scope.getValue()));
                }
            }
            if (!scopes.isEmpty()) {
                tenants.put(tenant.getKey(), Map.copyOf(scopes));
            }
        }
        return new Snapshot(version, Map.copyOf(definitions), Map.copyOf(tenants));
    }

    public long version() {
        return version;
    }

    public Map<String, SettingDefinition> definitions() {
        return definitions;
    }

    public Resolved resolve(String tenantId, String key, ScopePath path) {
        SettingDefinition definition = definitions.get(key);
        if (definition == null) {
            throw new UnknownSettingException(key);
        }
        Map<ScopeKey, Map<String, Object>> scopes = overrides.get(tenantId);
        if (scopes != null) {
            for (ScopeKey scope : path.fromMostSpecific()) {
                Map<String, Object> values = scopes.get(scope);
                Object value = values == null ? null : values.get(key);
                if (value != null) {
                    return new Resolved(value, scope.level(), version);
                }
            }
        }
        return new Resolved(definition.defaultValue(), ScopeLevel.GLOBAL, version);
    }

    public <T> T get(String tenantId, String key, ScopePath path, Class<T> type) {
        return type.cast(resolve(tenantId, key, path).value());
    }

    public Optional<Object> overrideAt(String tenantId, ScopeKey scope, String key) {
        Map<String, Object> values = tenantOverrides(tenantId).get(scope);
        return values == null ? Optional.empty() : Optional.ofNullable(values.get(key));
    }

    // for rollback diff
    public Map<ScopeKey, Map<String, Object>> tenantOverrides(String tenantId) {
        return overrides.getOrDefault(tenantId, Map.of());
    }

    public Set<String> tenantIds() {
        return overrides.keySet();
    }

    // copy-on-write: only this tenant's map and the scope maps the change set touches are copied, the rest is shared
    public Snapshot apply(long newVersion, ChangeSet changeSet) {
        requireNext(newVersion);
        Map<ScopeKey, Map<String, Object>> scopes = new HashMap<>(tenantOverrides(changeSet.tenantId()));
        Set<ScopeKey> touched = new HashSet<>();

        for (Operation op : changeSet.operations()) {
            SettingDefinition definition = definitions.get(op.key());
            if (definition == null) {
                throw new UnknownSettingException(op.key());
            }
            if (touched.add(op.scope())) {
                scopes.put(op.scope(), new HashMap<>(scopes.getOrDefault(op.scope(), Map.of())));
            }
            Map<String, Object> values = scopes.get(op.scope());
            if (op.op() == OpKind.SET) {
                values.put(op.key(), definition.type().parse(op.value()));
            } else {
                values.remove(op.key());
            }
        }

        for (ScopeKey scope : touched) {
            Map<String, Object> values = scopes.get(scope);
            if (values.isEmpty()) {
                scopes.remove(scope);
            } else {
                scopes.put(scope, Map.copyOf(values));
            }
        }
        Map<String, Map<ScopeKey, Map<String, Object>>> tenants = new HashMap<>(overrides);
        if (scopes.isEmpty()) {
            tenants.remove(changeSet.tenantId());
        } else {
            tenants.put(changeSet.tenantId(), Map.copyOf(scopes));
        }
        return new Snapshot(newVersion, definitions, Map.copyOf(tenants));
    }

    public Snapshot apply(ChangeEvent event) {
        return switch (event.payload()) {
            case OverridesChanged changed -> apply(event.version(), changed.changeSet());
            case DefinitionChanged changed -> withDefinition(event.version(), changed.definition());
        };
    }

    // add or replace
    public Snapshot withDefinition(long newVersion, SettingDefinition definition) {
        requireNext(newVersion);
        Map<String, SettingDefinition> next = new HashMap<>(definitions);
        next.put(definition.key(), definition);
        return new Snapshot(newVersion, Map.copyOf(next), overrides);
    }

    private void requireNext(long newVersion) {
        if (newVersion != version + 1) {
            throw new IllegalArgumentException("expected version " + (version + 1) + " but got " + newVersion);
        }
    }
}
