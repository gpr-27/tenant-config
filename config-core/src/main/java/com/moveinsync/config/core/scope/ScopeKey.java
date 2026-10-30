package com.moveinsync.config.core.scope;

public record ScopeKey(ScopeLevel level, String id) {

    public static final ScopeKey GLOBAL = new ScopeKey(ScopeLevel.GLOBAL, "*");

    public static ScopeKey client(String tenantId) {
        return new ScopeKey(ScopeLevel.CLIENT, tenantId);
    }

    public static ScopeKey office(String id) {
        return new ScopeKey(ScopeLevel.OFFICE, id);
    }

    public static ScopeKey shift(String id) {
        return new ScopeKey(ScopeLevel.SHIFT, id);
    }
}
