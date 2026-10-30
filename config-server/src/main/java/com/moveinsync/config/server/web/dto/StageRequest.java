package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StageRequest(@NotBlank String reason, @NotEmpty List<@NotBlank String> targetOffices,
                           @NotEmpty List<@Valid StagedValue> operations) {

    public record StagedValue(@NotBlank String key, @NotNull JsonNode value) {
    }
}
