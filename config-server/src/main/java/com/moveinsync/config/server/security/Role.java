package com.moveinsync.config.server.security;

public enum Role {
    ADMIN, OPERATOR, EMPLOYEE, SERVICE;

    public String authority() {
        return "ROLE_" + name();
    }
}
