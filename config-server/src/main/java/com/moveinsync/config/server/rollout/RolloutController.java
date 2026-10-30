package com.moveinsync.config.server.rollout;

import com.moveinsync.config.server.security.TenantGuard;
import com.moveinsync.config.server.web.dto.StageRequest;
import com.moveinsync.config.server.web.dto.StageResponse;
import com.moveinsync.config.server.web.dto.VersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/changes")
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
@RequiredArgsConstructor
public class RolloutController {

    private final RolloutService rollouts;

    @PostMapping("/staged")
    @ResponseStatus(HttpStatus.CREATED)
    public StageResponse stage(@PathVariable String tenantId, @Valid @RequestBody StageRequest request,
                               Authentication auth) {
        TenantGuard.check(auth, tenantId);
        return rollouts.stage(tenantId, request, auth.getName());
    }

    @PostMapping("/{version}/promote")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse promote(@PathVariable String tenantId, @PathVariable long version, Authentication auth) {
        TenantGuard.check(auth, tenantId);
        return new VersionResponse(rollouts.promote(tenantId, version, auth.getName()));
    }
}
