package com.moveinsync.config.core.change;

import java.time.Instant;

public record ChangeEvent(long version, ChangeKind kind, ChangePayload payload, Instant createdAt) {
}
