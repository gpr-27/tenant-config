package com.moveinsync.config.core.change;

import java.util.List;

// GLOBAL scope is rejected by ChangeSetValidator, not here, so it shows up in the error list
public record ChangeSet(String tenantId, String author, String reason, List<Operation> operations) {

    public ChangeSet {
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("a change set needs at least one operation");
        }
        operations = List.copyOf(operations);
    }
}
