package com.moveinsync.config.server.web.dto;

// tenantId is null for ADMIN and SERVICE
public record TokenResponse(String token, String role, String tenantId) {
}
