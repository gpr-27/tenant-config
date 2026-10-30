package com.moveinsync.config.core.change;

import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.error.InvalidValueException;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChangeSetValidator {

    private final Snapshot current;

    public ChangeSetValidator(Snapshot current) {
        this.current = current;
    }

    // collects every error before throwing, so an operator fixes the whole change set in one round
    public List<Operation> validate(ChangeSet changeSet) {
        List<ValidationError> errors = new ArrayList<>();
        List<Operation> parsed = new ArrayList<>();
        // what this change set leaves at each scope: the last SET of a key wins, an UNSET removes it
        Map<ScopeKey, Map<String, Object>> setValues = new HashMap<>();
        Map<ScopeKey, Set<String>> unsetKeys = new HashMap<>();
        List<Operation> operations = changeSet.operations();

        for (int i = 0; i < operations.size(); i++) {
            Operation op = operations.get(i);
            SettingDefinition definition = current.definitions().get(op.key());
            if (definition == null) {
                errors.add(new ValidationError(i, op.key(), "UNKNOWN_KEY", "no setting is defined with this key"));
                continue;
            }
            if (op.scope().level() == ScopeLevel.GLOBAL) {
                errors.add(new ValidationError(i, op.key(), "GLOBAL_SCOPE_NOT_ALLOWED",
                        "global values are changed through the setting definition's default"));
                continue;
            }
            // a CLIENT value is keyed by the tenant itself; any other id would be stored and never read
            if (op.scope().level() == ScopeLevel.CLIENT && !op.scope().id().equals(changeSet.tenantId())) {
                errors.add(new ValidationError(i, op.key(), "CLIENT_SCOPE_MISMATCH",
                        "a CLIENT scope id must be the tenant the change set is for"));
                continue;
            }
            if (op.op() == OpKind.UNSET) {
                setValues.getOrDefault(op.scope(), new HashMap<>()).remove(op.key());
                unsetKeys.computeIfAbsent(op.scope(), scope -> new HashSet<>()).add(op.key());
                parsed.add(new Operation(OpKind.UNSET, op.scope(), op.key(), null));
                continue;
            }

            Object value;
            try {
                value = definition.type().parse(op.value());
            } catch (InvalidValueException e) {
                errors.add(new ValidationError(i, op.key(), e.errorCode(), e.getMessage()));
                continue;
            }
            // recorded before the rule check so an out-of-range value still gets its cross-field verdict
            setValues.computeIfAbsent(op.scope(), scope -> new HashMap<>()).put(op.key(), value);
            unsetKeys.getOrDefault(op.scope(), new HashSet<>()).remove(op.key());
            try {
                definition.type().validate(value, definition.rules());
            } catch (InvalidValueException e) {
                errors.add(new ValidationError(i, op.key(), e.errorCode(), e.getMessage()));
                continue;
            }
            parsed.add(new Operation(OpKind.SET, op.scope(), op.key(), definition.type().toJson(value)));
        }

        checkOrder(changeSet, setValues, unsetKeys, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return parsed;
    }

    // for a SET of K at scope S with lessThan P, compare with P as it will be once this change set is applied
    private void checkOrder(ChangeSet changeSet, Map<ScopeKey, Map<String, Object>> setValues,
                            Map<ScopeKey, Set<String>> unsetKeys, List<ValidationError> errors) {
        List<Operation> operations = changeSet.operations();
        for (int i = 0; i < operations.size(); i++) {
            Operation op = operations.get(i);
            Map<String, Object> atScope = setValues.getOrDefault(op.scope(), Map.of());
            Object value = atScope.get(op.key());
            if (op.op() != OpKind.SET || value == null) {
                continue;
            }
            String partner = current.definitions().get(op.key()).rules().lessThan();
            if (partner == null) {
                continue;
            }
            Object partnerValue = atScope.get(partner);
            if (partnerValue == null) {
                boolean removedHere = unsetKeys.getOrDefault(op.scope(), Set.of()).contains(partner);
                partnerValue = resolvePartner(changeSet.tenantId(), partner, op.scope(), removedHere);
            }
            if (!isLess(value, partnerValue)) {
                errors.add(new ValidationError(i, op.key(), "CROSS_FIELD_ORDER",
                        op.key() + " must be less than " + partner + " at the same scope"));
            }
        }
    }

    // an UNSET of the partner at S in this change set means its value will come from the level above S
    private Object resolvePartner(String tenantId, String partner, ScopeKey scope, boolean removedHere) {
        if (!removedHere) {
            return current.resolve(tenantId, partner, pathTo(tenantId, scope)).value();
        }
        if (scope.level() == ScopeLevel.CLIENT) {
            return current.definitions().get(partner).defaultValue();
        }
        return current.resolve(tenantId, partner, new ScopePath(tenantId, null, null)).value();
    }

    // shift-to-office membership is not modelled, so a SHIFT check does not see an OFFICE-level partner
    private static ScopePath pathTo(String tenantId, ScopeKey scope) {
        return switch (scope.level()) {
            case OFFICE -> new ScopePath(tenantId, scope.id(), null);
            case SHIFT -> new ScopePath(tenantId, null, scope.id());
            case CLIENT, GLOBAL -> new ScopePath(tenantId, null, null);
        };
    }

    // lessThan pairs are both DURATION or both NUMBER; any other pairing has no order to check
    private static boolean isLess(Object value, Object partner) {
        if (value instanceof Duration duration && partner instanceof Duration limit) {
            return duration.compareTo(limit) < 0;
        }
        if (value instanceof BigDecimal number && partner instanceof BigDecimal limit) {
            return number.compareTo(limit) < 0;
        }
        return true;
    }
}
