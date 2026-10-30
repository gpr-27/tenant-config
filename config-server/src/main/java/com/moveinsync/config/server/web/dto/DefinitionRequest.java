package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DefinitionRequest(@NotBlank String key, @NotBlank String type, JsonNode rules,
                                @JsonProperty("default") @NotNull JsonNode defaultValue, String description) {
}
