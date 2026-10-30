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
import java.util.List;
import java.util.Map;

public final class ChangeSetValidator {

    private final Snapshot current;

    public ChangeSetValidator(Snapshot current) {
        this.current = current;
    }

    // collects every error before throwing, so an operator fixes the whole change set in one round
    public List<Operation> validate(ChangeSet changeSet) {
        List<ValidationError> errors = new ArrayList<>();
        List<Operation> parsed = new ArrayList<>();
        Map<ScopeKey, Map<String, Object>> setValues = new HashMap<>();
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
            if (op.op() == OpKind.UNSET) {
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
            try {
                definition.type().validate(value, definition.rules());
            } catch (InvalidValueException e) {
                errors.add(new ValidationError(i, op.key(), e.errorCode(), e.getMessage()));
                continue;
            }
            parsed.add(new Operation(OpKind.SET, op.scope(), op.key(), definition.type().toJson(value)));
        }

        checkOrder(changeSet, setValues, errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return parsed;
    }

    // for a SET of K at scope S with lessThan P: P comes from this change set at S, else from current at path(S)
    private void checkOrder(ChangeSet changeSet, Map<ScopeKey, Map<String, Object>> setValues,
                            List<ValidationError> errors) {
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
                partnerValue = current.resolve(changeSet.tenantId(), partner, pathTo(changeSet.tenantId(), op.scope()))
                        .value();
            }
            if (!isLess(value, partnerValue)) {
                errors.add(new ValidationError(i, op.key(), "CROSS_FIELD_ORDER",
                        op.key() + " must be less than " + partner + " at the same scope"));
            }
        }
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
