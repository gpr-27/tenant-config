package com.moveinsync.config.server.web.dto;

import jakarta.validation.constraints.NotNull;

public record RollbackRequest(@NotNull Long targetVersion, Boolean dryRun) {

    // a body without dryRun only previews: rewriting a tenant's config takes an explicit "dryRun": false
    public boolean previewOnly() {
        return !Boolean.FALSE.equals(dryRun);
    }
}
