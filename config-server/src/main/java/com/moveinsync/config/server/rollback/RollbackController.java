package com.moveinsync.config.server.rollback;

import com.moveinsync.config.server.security.TenantGuard;
import com.moveinsync.config.server.web.dto.RollbackRequest;
import com.moveinsync.config.server.web.dto.RollbackResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RollbackController {

    private final RollbackService rollbacks;

    @PostMapping("/api/v1/tenants/{tenantId}/rollback")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public RollbackResponse rollback(@PathVariable String tenantId, @Valid @RequestBody RollbackRequest request,
                                     Authentication auth) {
        TenantGuard.check(auth, tenantId);
        return rollbacks.rollback(tenantId, request.targetVersion(), request.previewOnly(), auth.getName());
    }
}
