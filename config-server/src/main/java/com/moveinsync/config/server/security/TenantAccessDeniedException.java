package com.moveinsync.config.server.security;

public class TenantAccessDeniedException extends RuntimeException {

    public TenantAccessDeniedException() {
        super("not found");
    }
}
