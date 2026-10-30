package com.moveinsync.config.server.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public final class TenantGuard {

    private TenantGuard() {
    }

    // 404 rather than 403, so a caller cannot learn that another tenant exists.
    // "*" is where definition changes are logged, not a tenant: not even an ADMIN may roll it back or promote it.
    public static void check(Authentication auth, String tenantId) {
        if (tenantId.equals("*") || (!seesAllTenants(auth) && !tenantId.equals(tenantOf(auth)))) {
            throw new TenantAccessDeniedException();
        }
    }

    public static boolean seesAllTenants(Authentication auth) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (name.equals(Role.ADMIN.authority()) || name.equals(Role.SERVICE.authority())) {
                return true;
            }
        }
        return false;
    }

    // JwtAuthFilter stores the tenantId claim as the authentication details
    public static String tenantOf(Authentication auth) {
        return auth.getDetails() instanceof String tenantId ? tenantId : null;
    }
}
