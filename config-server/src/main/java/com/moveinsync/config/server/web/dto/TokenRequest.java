package com.moveinsync.config.server.web.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(@NotBlank String username, @NotBlank String password) {
}
