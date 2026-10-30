package com.moveinsync.config.server.web.dto;

import jakarta.validation.constraints.NotNull;

public record RollbackRequest(@NotNull Long targetVersion, boolean dryRun) {
}
