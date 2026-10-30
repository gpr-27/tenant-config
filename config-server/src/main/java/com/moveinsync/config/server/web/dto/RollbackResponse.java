package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

// version is absent for a dry run and for an empty diff, because no change was written
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RollbackResponse(long targetVersion, JsonNode diff, Long version) {
}
