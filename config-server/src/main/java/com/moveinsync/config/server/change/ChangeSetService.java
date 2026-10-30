package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.ChangeSetValidator;
import com.moveinsync.config.core.change.DefinitionChanged;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.server.definition.DefinitionEntity;
import com.moveinsync.config.server.definition.DefinitionImpact;
import com.moveinsync.config.server.definition.DefinitionRepository;
import com.moveinsync.config.server.metrics.ServerMetrics;
import com.moveinsync.config.server.push.PushHub;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import com.moveinsync.config.server.state.StateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeSetService {

    // Writes are serialised on purpose. Two concurrent submits could otherwise draw versions 10 and 11 and
    // commit/publish them as 11 then 10: clients would see a gap and our own Snapshot.apply could not take 11
    // before 10. One lock around allocate -> commit -> apply -> publish keeps those three orders identical.
    // Fair, so operators are served in arrival order; at a few changes a minute the waiting costs nothing.
    private final ReentrantLock writeLock = new ReentrantLock(true);

    // programmatic, so the commit is finished while we still hold the lock
    private final TransactionTemplate tx;
    private final ChangeRepository changes;
    private final OverrideRepository overrides;
    private final DefinitionRepository definitions;
    private final IdempotencyGuard idempotency;
    private final CurrentState currentState;
    private final RecentChanges recentChanges;
    private final PushHub pushHub;
    private final ServerMetrics metrics;
    private final Rejections rejections;
    // a provider, because StateLoader itself reaches this service through DefinitionService.seed
    private final ObjectProvider<StateLoader> stateLoader;

    public long submit(ChangeSet changeSet, ChangeKind kind, String idempotencyKey) {
        writeLock.lock();
        try {
            stateLoader.getObject().catchUp();
            String requestHash = idempotencyKey == null ? null : idempotency.requestHash(changeSet);
            Optional<Long> earlier =
                    idempotency.previousVersion(changeSet.tenantId(), kind, idempotencyKey, requestHash);
            if (earlier.isPresent()) {
                return earlier.get();
            }

            List<Operation> parsed = validate(changeSet, kind);
            ChangeSet accepted = new ChangeSet(changeSet.tenantId(), changeSet.author(), changeSet.reason(), parsed);
            Instant now = now();
            long version = commit(() -> {
                long allocated = changes.append(accepted, kind, idempotencyKey, requestHash, now);
                overrides.applyAll(accepted.tenantId(), parsed, allocated);
                return allocated;
            });

            // Publish only after commit. Pushing from inside the transaction and then failing the commit would
            // hand clients a version that never existed in the database.
            currentState.apply(version, accepted);
            announce(new ChangeEvent(version, kind, new OverridesChanged(accepted), now));
            return version;
        } finally {
            writeLock.unlock();
        }
    }

    public long submitDefinition(SettingDefinition definition, String author, String reason) {
        writeLock.lock();
        try {
            stateLoader.getObject().catchUp();
            List<ValidationError> errors = new DefinitionImpact(currentState.snapshot()).check(definition);
            if (!errors.isEmpty()) {
                rejections.invalid(ChangeKind.DEFINITION, "*", errors);
                throw new ValidationException(errors);
            }
            Instant now = now();
            long version = commit(() -> {
                long allocated = changes.appendDefinition(definition, author, reason, now);
                definitions.save(DefinitionEntity.from(definition, allocated));
                return allocated;
            });

            currentState.applyDefinition(version, definition);
            announce(new ChangeEvent(version, ChangeKind.DEFINITION, new DefinitionChanged(definition), now));
            return version;
        } finally {
            writeLock.unlock();
        }
    }

    // for a caller whose read of the current state must not go stale before its submit (the lock is reentrant)
    public <T> T underWriteLock(Supplier<T> work) {
        writeLock.lock();
        try {
            return work.get();
        } finally {
            writeLock.unlock();
        }
    }

    private List<Operation> validate(ChangeSet changeSet, ChangeKind kind) {
        try {
            return new ChangeSetValidator(currentState.snapshot()).validate(changeSet);
        } catch (ValidationException e) {
            rejections.invalid(kind, changeSet.tenantId(), e.errors());
            throw e;
        }
    }

    private long commit(LongSupplier work) {
        return tx.execute(status -> {
            // a rolled-back insert still consumed its nextval; winding the sequence back to the live version
            // first keeps versions gap-free, which Snapshot.apply (exactly +1) and the clients rely on
            changes.rewindSequence(currentState.snapshot().version());
            return work.getAsLong();
        });
    }

    private void announce(ChangeEvent event) {
        recentChanges.add(event);
        pushHub.publish(event);
        metrics.changeAccepted(event.kind());
        log.info("change set committed version={} kind={}", event.version(), event.kind());
    }

    // the column is timestamptz (microseconds); truncating keeps the pushed event equal to the stored row
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
