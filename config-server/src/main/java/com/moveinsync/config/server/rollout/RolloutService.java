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
import com.moveinsync.config.server.change.ChangeEntity;
import com.moveinsync.config.server.change.ChangeRepository;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.rollback.VersionNotFoundException;
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

    // one change set moves each key to CLIENT level and removes the office copies, so it lands as one version
    public long promote(String tenantId, long stagedVersion, String author) {
        ChangeEntity staged = changes.findById(stagedVersion)
                .filter(change -> change.getTenantId().equals(tenantId))
                .orElseThrow(() -> new VersionNotFoundException(stagedVersion));
        List<Operation> stagedOperations = ChangeEventCodec.operationsFromJson(staged.operationsJson());
        if (!staged.getKind().equals(ChangeKind.CHANGE.name()) || !allOfficeSets(stagedOperations)) {
            throw new ValidationException(List.of(new ValidationError(0, null, "NOT_A_STAGED_CHANGE",
                    "only a CHANGE made of OFFICE-level SET operations can be promoted")));
        }

        Map<String, JsonNode> clientValues = new LinkedHashMap<>();
        List<Operation> unsets = new ArrayList<>();
        for (Operation op : stagedOperations) {
            clientValues.putIfAbsent(op.key(), op.value());
            unsets.add(new Operation(OpKind.UNSET, op.scope(), op.key(), null));
        }
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> value : clientValues.entrySet()) {
            operations.add(new Operation(OpKind.SET, ScopeKey.client(tenantId), value.getKey(), value.getValue()));
        }
        operations.addAll(unsets);

        String reason = "promote #" + stagedVersion + ": " + staged.getReason();
        return changeSets.submit(new ChangeSet(tenantId, author, reason, operations), ChangeKind.PROMOTE, null);
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
