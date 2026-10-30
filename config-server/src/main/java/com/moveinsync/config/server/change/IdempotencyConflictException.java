package com.moveinsync.config.server.change;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("this Idempotency-Key was already used with a different request body");
    }
}
