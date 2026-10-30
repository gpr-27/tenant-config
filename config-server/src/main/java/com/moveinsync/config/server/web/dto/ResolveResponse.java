package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

// source and version are only filled when the caller asks for explain
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResolveResponse(String key, JsonNode value, String source, Long version) {
}
