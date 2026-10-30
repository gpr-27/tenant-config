package com.moveinsync.config.server.rollout;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.change.ChangeEntity;
import com.moveinsync.config.server.change.ChangeRepository;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.change.Rejections;
import com.moveinsync.config.server.rollback.Diff;
import com.moveinsync.config.server.rollback.VersionNotFoundException;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.web.dto.StageRequest;
import com.moveinsync.config.server.web.dto.StageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// staged rollout reuses plain change sets: "two offices first" is the value written at OFFICE level
@Service
@RequiredArgsConstructor
public class RolloutService {

    private final ChangeSetService changeSets;
    private final ChangeRepository changes;
    private final Rejections rejections;
    private final CurrentState currentState;

    public StageResponse stage(String tenantId, StageRequest request, String author) {
        List<Operation> operations = new ArrayList<>();
        for (String office : request.targetOffices()) {
            for (StageRequest.StagedValue staged : request.operations()) {
                operations.add(new Operation(OpKind.SET, ScopeKey.office(office), staged.key(), staged.value()));
            }
        }
        ChangeSet changeSet = new ChangeSet(tenantId, author, request.reason(), operations);
        long version = changeSets.submit(changeSet, ChangeKind.CHANGE, null);
        return new StageResponse(version, List.copyOf(request.targetOffices()));
    }

    // One change set moves each key to CLIENT level and removes the office copies, so it lands as one version.
    // Judged under the write lock, so no change can land between "the offices still hold it" and the promote.
    public long promote(String tenantId, long stagedVersion, String author) {
        return changeSets.underWriteLock(() -> {
            ChangeEntity staged = changes.findById(stagedVersion)
                    .filter(change -> change.getTenantId().equals(tenantId))
                    .orElseThrow(() -> new VersionNotFoundException(stagedVersion));
            List<Operation> stagedOperations = ChangeEventCodec.operationsFromJson(staged.operationsJson());
            if (!staged.getKind().equals(ChangeKind.CHANGE.name()) || !allOfficeSets(stagedOperations)) {
                throw notStaged(tenantId, "only a CHANGE made of OFFICE-level SET operations can be promoted");
            }

            Map<String, JsonNode> clientValues = new LinkedHashMap<>();
            List<Operation> unsets = new ArrayList<>();
            for (Operation op : stagedOperations) {
                // a client-wide value only makes sense when every staged office was given the same one
                JsonNode earlier = clientValues.putIfAbsent(op.key(), op.value());
                if (earlier != null && !earlier.equals(op.value())) {
                    throw notStaged(tenantId, "the offices disagree on the value for " + op.key());
                }
                unsets.add(new Operation(OpKind.UNSET, op.scope(), op.key(), null));
            }
            checkStillCurrent(tenantId, stagedOperations);

            List<Operation> operations = new ArrayList<>();
            for (Map.Entry<String, JsonNode> value : clientValues.entrySet()) {
                operations.add(new Operation(OpKind.SET, ScopeKey.client(tenantId), value.getKey(), value.getValue()));
            }
            operations.addAll(unsets);

            String reason = "promote #" + stagedVersion + ": " + staged.getReason();
            return changeSets.submit(new ChangeSet(tenantId, author, reason, operations), ChangeKind.PROMOTE, null);
        });
    }

    // an office changed, removed or already promoted since staging would otherwise have its newer value replayed
    // over at CLIENT level; this is also what stops the same version being promoted twice
    private void checkStillCurrent(String tenantId, List<Operation> stagedOperations) {
        Snapshot snapshot = currentState.snapshot();
        for (Operation op : stagedOperations) {
            Object staged = snapshot.definitions().get(op.key()).type().parse(op.value());
            Object now = snapshot.overrideAt(tenantId, op.scope(), op.key()).orElse(null);
            if (now == null || !Diff.same(staged, now)) {
                rejections.conflict(ChangeKind.PROMOTE, tenantId, "STAGED_CHANGE_SUPERSEDED");
                throw new StagedChangeSupersededException(op.scope().id(), op.key());
            }
        }
    }

    private ValidationException notStaged(String tenantId, String message) {
        List<ValidationError> errors = List.of(new ValidationError(0, null, "NOT_A_STAGED_CHANGE", message));
        rejections.invalid(ChangeKind.PROMOTE, tenantId, errors);
        return new ValidationException(errors);
    }

    private static boolean allOfficeSets(List<Operation> operations) {
        for (Operation op : operations) {
            if (op.op() != OpKind.SET || op.scope().level() != ScopeLevel.OFFICE) {
                return false;
            }
        }
        return true;
    }
}
