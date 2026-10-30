package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

// every field optional; what is missing keeps its current value
public record DefinitionUpdate(JsonNode rules, @JsonProperty("default") JsonNode defaultValue, String description) {
}
