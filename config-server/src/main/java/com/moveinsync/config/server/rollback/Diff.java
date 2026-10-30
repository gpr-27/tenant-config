package com.moveinsync.config.server.rollback;

import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.scope.ScopeKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class Diff {

    private record Slot(ScopeKey scope, String key) {
    }

    // level, then scope id, then key: the same two states always give the same list
    private static final Comparator<Slot> ORDER = Comparator.<Slot, Integer>comparing(slot -> slot.scope().level().ordinal())
            .thenComparing(slot -> slot.scope().id())
            .thenComparing(Slot::key);

    private Diff() {
    }

    // the operations that turn `current` into `target`: SET what target has differently, UNSET what only current has
    public static List<Operation> between(Map<ScopeKey, Map<String, Object>> current,
                                          Map<ScopeKey, Map<String, Object>> target,
                                          Map<String, SettingDefinition> definitions) {
        TreeSet<Slot> slots = new TreeSet<>(ORDER);
        addSlots(current, slots);
        addSlots(target, slots);

        List<Operation> operations = new ArrayList<>();
        for (Slot slot : slots) {
            Object now = current.getOrDefault(slot.scope(), Map.of()).get(slot.key());
            Object then = target.getOrDefault(slot.scope(), Map.of()).get(slot.key());
            if (then == null) {
                operations.add(new Operation(OpKind.UNSET, slot.scope(), slot.key(), null));
            } else if (!then.equals(now)) {
                SettingDefinition definition = definitions.get(slot.key());
                operations.add(new Operation(OpKind.SET, slot.scope(), slot.key(), definition.type().toJson(then)));
            }
        }
        return operations;
    }

    private static void addSlots(Map<ScopeKey, Map<String, Object>> state, TreeSet<Slot> slots) {
        for (Map.Entry<ScopeKey, Map<String, Object>> scope : state.entrySet()) {
            for (String key : scope.getValue().keySet()) {
                slots.add(new Slot(scope.getKey(), key));
            }
        }
    }
}
