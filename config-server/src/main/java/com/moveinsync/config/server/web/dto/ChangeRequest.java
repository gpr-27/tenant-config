package com.moveinsync.config.server.web.dto;

import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public record ChangeRequest(@NotBlank @Size(max = 500) String reason, @NotEmpty List<@Valid OperationRequest> operations) {

    public ChangeSet toChangeSet(String tenantId, String author) {
        List<Operation> converted = new ArrayList<>();
        for (OperationRequest operation : operations) {
            converted.add(operation.toOperation());
        }
        return new ChangeSet(tenantId, author, reason, converted);
    }
}
