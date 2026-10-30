package com.moveinsync.config.server.rollback;

import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.server.change.ChangeEntity;
import com.moveinsync.config.server.change.ChangeRepository;
import com.moveinsync.config.server.state.CurrentState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

// the log is the source of truth: a tenant's overrides at version T are its change sets up to T, replayed in order
@Component
@RequiredArgsConstructor
public class StateReplayer {

    private final ChangeRepository changes;
    private final CurrentState currentState;

    public Map<ScopeKey, Map<String, Object>> stateAt(String tenantId, long target) {
        Map<String, SettingDefinition> definitions = currentState.snapshot().definitions();
        Map<ScopeKey, Map<String, Object>> state = new HashMap<>();
        for (ChangeEntity change : changes.findByTenantIdAndVersionLessThanEqualOrderByVersionAsc(tenantId, target)) {
            for (Operation op : ChangeEventCodec.operationsFromJson(change.operationsJson())) {
                Map<String, Object> values = state.computeIfAbsent(op.scope(), scope -> new HashMap<>());
                if (op.op() == OpKind.SET) {
                    values.put(op.key(), definitions.get(op.key()).type().parse(op.value()));
                } else {
                    values.remove(op.key());
                }
            }
        }
        state.values().removeIf(Map::isEmpty);
        return state;
    }
}
