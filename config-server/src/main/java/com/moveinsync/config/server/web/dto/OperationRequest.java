package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// value is ignored for UNSET; for SET a missing value fails validation as TYPE_MISMATCH
public record OperationRequest(@NotNull OpKind op, @NotNull ScopeLevel level, @NotBlank String scopeId,
                               @NotBlank String key, JsonNode value) {

    public Operation toOperation() {
        return new Operation(op, new ScopeKey(level, scopeId), key, op == OpKind.SET ? value : null);
    }
}
