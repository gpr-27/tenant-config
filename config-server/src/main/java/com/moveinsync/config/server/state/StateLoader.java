package com.moveinsync.config.server.state;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.change.ChangeEntity;
import com.moveinsync.config.server.change.ChangeRepository;
import com.moveinsync.config.server.change.OverrideEntity;
import com.moveinsync.config.server.change.OverrideRepository;
import com.moveinsync.config.server.definition.DefinitionEntity;
import com.moveinsync.config.server.definition.DefinitionRepository;
import com.moveinsync.config.server.definition.DefinitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// rebuilds memory from the database at start-up: values + "latest version" + the recent-changes ring.
// Runs once all beans exist but before the web server starts listening, so no request ever sees version 0.
@Slf4j
@Component
@RequiredArgsConstructor
public class StateLoader implements SmartInitializingSingleton {

    private final DefinitionRepository definitions;
    private final OverrideRepository overrides;
    private final ChangeRepository changes;
    private final DefinitionService definitionService;
    private final CurrentState currentState;
    private final RecentChanges recentChanges;

    @Override
    public void afterSingletonsInstantiated() {
        load();
    }

    // A commit can succeed in the database while its acknowledgement is lost, leaving memory behind. The next
    // write would rewind the sequence to that stale version and collide with the stored row, so reload first.
    // Called by ChangeSetService under its write lock.
    public void catchUp() {
        long stored = changes.maxVersion();
        long inMemory = currentState.snapshot().version();
        if (stored > inMemory) {
            log.warn("database is at version={} but memory at version={}, reloading state", stored, inMemory);
            load();
        }
    }

    public void load() {
        Map<String, SettingDefinition> byKey = new HashMap<>();
        for (DefinitionEntity entity : definitions.findAll()) {
            SettingDefinition definition = entity.toDefinition();
            byKey.put(definition.key(), definition);
        }

        Map<String, Map<ScopeKey, Map<String, Object>>> values = new HashMap<>();
        for (OverrideEntity row : overrides.findAll()) {
            Object value = byKey.get(row.getKey()).type().parse(row.getValue());
            values.computeIfAbsent(row.getTenantId(), tenant -> new HashMap<>())
                    .computeIfAbsent(row.getScope(), scope -> new HashMap<>())
                    .put(row.getKey(), value);
        }

        long version = changes.maxVersion();
        List<ChangeEvent> recent = new ArrayList<>();
        for (ChangeEntity change : changes.findByOrderByVersionDesc(Pageable.ofSize(recentChanges.capacity()))) {
            recent.add(change.toEvent());
        }
        Collections.reverse(recent);

        // ring first: a reader that sees the new version must also find its events in the ring
        recentChanges.replaceAll(recent);
        currentState.replace(Snapshot.of(version, byKey, values));
        log.info("state loaded version={} definitions={} recentChanges={}", version, byKey.size(), recent.size());

        if (byKey.isEmpty()) {
            definitionService.seed();
            log.info("seeded definitions, now at version={}", currentState.snapshot().version());
        }
    }
}
