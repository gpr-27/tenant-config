package com.moveinsync.config.server.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Resolved;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.metrics.ServerMetrics;
import com.moveinsync.config.server.security.TenantGuard;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.web.dto.ResolveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ResolveController {

    private final CurrentState currentState;
    private final ServerMetrics metrics;

    @GetMapping("/api/v1/tenants/{tenantId}/resolve")
    @PreAuthorize("isAuthenticated()")
    public ResolveResponse resolve(@PathVariable String tenantId, @RequestParam String key,
                                   @RequestParam(required = false) String officeId,
                                   @RequestParam(required = false) String shiftId,
                                   @RequestParam(defaultValue = "false") boolean explain, Authentication auth) {
        TenantGuard.check(auth, tenantId);
        Snapshot snapshot = currentState.snapshot();
        ScopePath path = new ScopePath(tenantId, officeId, shiftId);
        Resolved resolved = metrics.timeResolve(() -> snapshot.resolve(tenantId, key, path));
        JsonNode value = snapshot.definitions().get(key).type().toJson(resolved.value());
        if (!explain) {
            return new ResolveResponse(key, value, null, null);
        }
        return new ResolveResponse(key, value, resolved.source().name(), resolved.version());
    }
}
