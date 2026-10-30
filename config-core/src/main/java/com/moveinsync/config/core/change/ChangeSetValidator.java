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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ChangeSetValidator {

    private final Snapshot current;

    public ChangeSetValidator(Snapshot current) {
        this.current = current;
    }

    // collects every error before throwing, so an operator fixes the whole change set in one round
    public List<Operation> validate(ChangeSet changeSet) {
        List<ValidationError> errors = new ArrayList<>();
        List<Operation> parsed = new ArrayList<>();
        // every operation that can be applied, out-of-range values included, so the ordering rule can judge them too
        List<Operation> applicable = new ArrayList<>();
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
                Operation unset = new Operation(OpKind.UNSET, op.scope(), op.key(), null);
                applicable.add(unset);
                parsed.add(unset);
                continue;
            }

            Object value;
            try {
                value = definition.type().parse(op.value());
            } catch (InvalidValueException e) {
                errors.add(new ValidationError(i, op.key(), e.errorCode(), e.getMessage()));
                continue;
            }
            Operation set = new Operation(OpKind.SET, op.scope(), op.key(), definition.type().toJson(value));
            applicable.add(set);
            try {
                definition.type().validate(value, definition.rules());
            } catch (InvalidValueException e) {
                errors.add(new ValidationError(i, op.key(), e.errorCode(), e.getMessage()));
                continue;
            }
            parsed.add(set);
        }

        if (!applicable.isEmpty()) {
            ChangeSet trial = new ChangeSet(changeSet.tenantId(), changeSet.author(), changeSet.reason(), applicable);
            checkOrder(changeSet, current.apply(current.version() + 1, trial), errors);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return parsed;
    }

    // Every lessThan pair the change set touches must hold everywhere in the tenant once it is applied: lowering
    // maxAdvance at CLIENT can break an OFFICE cutoff, removing an office value can expose the one above it.
    // A place whose pair this change set does not alter is left alone, so an old problem is not blamed on it.
    private void checkOrder(ChangeSet changeSet, Snapshot after, List<ValidationError> errors) {
        String tenantId = changeSet.tenantId();
        for (SettingDefinition definition : current.definitions().values()) {
            String key = definition.key();
            String partner = definition.rules().lessThan();
            if (partner == null || !current.definitions().containsKey(partner) || !touches(changeSet, key, partner)) {
                continue;
            }
            for (ScopeKey scope : placesToCheck(changeSet, after)) {
                ScopePath path = pathTo(tenantId, scope);
                Object value = after.resolve(tenantId, key, path).value();
                Object limit = after.resolve(tenantId, partner, path).value();
                boolean changed = !value.equals(current.resolve(tenantId, key, path).value())
                        || !limit.equals(current.resolve(tenantId, partner, path).value());
                if (changed && !isLess(value, limit)) {
                    int blamed = blame(changeSet, key, partner, scope);
                    errors.add(new ValidationError(blamed, changeSet.operations().get(blamed).key(), "CROSS_FIELD_ORDER",
                            key + " must be less than " + partner + " at " + scope.level() + " " + scope.id()));
                }
            }
        }
    }

    private static boolean touches(ChangeSet changeSet, String key, String partner) {
        for (Operation op : changeSet.operations()) {
            if (op.key().equals(key) || op.key().equals(partner)) {
                return true;
            }
        }
        return false;
    }

    // the tenant's CLIENT level, every scope holding a value before or after, and every scope the change set names
    private Set<ScopeKey> placesToCheck(ChangeSet changeSet, Snapshot after) {
        Set<ScopeKey> places = new TreeSet<>(Comparator.comparing(ScopeKey::level).thenComparing(ScopeKey::id));
        places.add(ScopeKey.client(changeSet.tenantId()));
        places.addAll(current.tenantOverrides(changeSet.tenantId()).keySet());
        places.addAll(after.tenantOverrides(changeSet.tenantId()).keySet());
        for (Operation op : changeSet.operations()) {
            if (op.scope().level() != ScopeLevel.GLOBAL) {
                places.add(op.scope());
            }
        }
        return places;
    }

    // the operation to point at: the lower key at that scope, else the partner there, else the first one on either
    private static int blame(ChangeSet changeSet, String key, String partner, ScopeKey scope) {
        List<Operation> operations = changeSet.operations();
        for (String wanted : List.of(key, partner)) {
            for (int i = 0; i < operations.size(); i++) {
                if (operations.get(i).key().equals(wanted) && operations.get(i).scope().equals(scope)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < operations.size(); i++) {
            if (operations.get(i).key().equals(key) || operations.get(i).key().equals(partner)) {
                return i;
            }
        }
        return 0;
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
