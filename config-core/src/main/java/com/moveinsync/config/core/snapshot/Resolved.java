package com.moveinsync.config.core.snapshot;

import com.moveinsync.config.core.scope.ScopeLevel;

// version = the snapshot version that answered
public record Resolved(Object value, ScopeLevel source, long version) {
}
