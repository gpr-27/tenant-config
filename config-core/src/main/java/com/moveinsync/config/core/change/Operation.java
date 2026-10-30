package com.moveinsync.config.core.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.scope.ScopeKey;

// value is null for UNSET
public record Operation(OpKind op, ScopeKey scope, String key, JsonNode value) {
}
