package com.moveinsync.config.server.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ChangeLogEntry(long version, String author, String reason, String kind, Instant createdAt,
                             JsonNode operations) {
}
