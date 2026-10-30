package com.moveinsync.config.server.definition;

import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.error.InvalidValueException;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// what a definition change would do to the values already live. Migrating them is out of scope, so a change
// that strands an override or breaks a lessThan pair is refused instead.
public final class DefinitionImpact {

    private final Snapshot current;

    public DefinitionImpact(Snapshot current) {
        this.current = current;
    }

    public List<ValidationError> check(SettingDefinition definition) {
        List<ValidationError> errors = new ArrayList<>();
        checkOverrides(definition, errors);
        // judged on the snapshot as it would be, so both sides of a pair see the new default and rules
        Snapshot next = current.withDefinition(current.version() + 1, definition);
        for (SettingDefinition lower : next.definitions().values()) {
            String upper = lower.rules().lessThan();
            boolean touched = lower.key().equals(definition.key()) || definition.key().equals(upper);
            if (upper != null && touched && next.definitions().containsKey(upper)) {
                checkOrder(next, definition.key(), lower.key(), upper, errors);
            }
        }
        return errors;
    }

    private void checkOverrides(SettingDefinition definition, List<ValidationError> errors) {
        for (String tenantId : current.tenantIds()) {
            for (Map.Entry<ScopeKey, Map<String, Object>> scope : current.tenantOverrides(tenantId).entrySet()) {
                Object value = scope.getValue().get(definition.key());
                if (value == null) {
                    continue;
                }
                try {
                    definition.type().validate(value, definition.rules());
                } catch (InvalidValueException e) {
                    String message = "existing override at " + where(tenantId, scope.getKey())
                            + " would break: " + e.getMessage();
                    errors.add(new ValidationError(0, definition.key(), e.errorCode(), message));
                }
            }
        }
    }

    private static void checkOrder(Snapshot next, String changedKey, String lower, String upper,
                                   List<ValidationError> errors) {
        String rule = lower + " must be less than " + upper;
        if (!isLess(next.definitions().get(lower).defaultValue(), next.definitions().get(upper).defaultValue())) {
            errors.add(new ValidationError(0, changedKey, "CROSS_FIELD_ORDER", rule + " at GLOBAL"));
        }
        for (String tenantId : next.tenantIds()) {
            for (Map.Entry<ScopeKey, Map<String, Object>> scope : next.tenantOverrides(tenantId).entrySet()) {
                Map<String, Object> values = scope.getValue();
                if (!values.containsKey(lower) && !values.containsKey(upper)) {
                    continue;
                }
                ScopePath path = pathTo(tenantId, scope.getKey());
                if (!isLess(next.resolve(tenantId, lower, path).value(), next.resolve(tenantId, upper, path).value())) {
                    errors.add(new ValidationError(0, changedKey, "CROSS_FIELD_ORDER",
                            rule + " at " + where(tenantId, scope.getKey())));
                }
            }
        }
    }

    // the same path ChangeSetValidator compares at: a SHIFT does not see OFFICE values (membership is not modelled)
    private static ScopePath pathTo(String tenantId, ScopeKey scope) {
        return switch (scope.level()) {
            case OFFICE -> new ScopePath(tenantId, scope.id(), null);
            case SHIFT -> new ScopePath(tenantId, null, scope.id());
            case CLIENT, GLOBAL -> new ScopePath(tenantId, null, null);
        };
    }

    // lessThan pairs are both DURATION or both NUMBER
    private static boolean isLess(Object value, Object limit) {
        if (value instanceof Duration duration && limit instanceof Duration max) {
            return duration.compareTo(max) < 0;
        }
        if (value instanceof BigDecimal number && limit instanceof BigDecimal max) {
            return number.compareTo(max) < 0;
        }
        return true;
    }

    private static String where(String tenantId, ScopeKey scope) {
        return tenantId + "/" + scope.level() + "/" + scope.id();
    }
}
