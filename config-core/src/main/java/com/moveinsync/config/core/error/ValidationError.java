package com.moveinsync.config.core.error;

public record ValidationError(int operationIndex, String key, String code, String message) {
}
