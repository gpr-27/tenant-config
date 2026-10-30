package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopeLevel;
import com.moveinsync.config.server.security.TenantGuard;
import com.moveinsync.config.server.web.dto.ChangeLogEntry;
import com.moveinsync.config.server.web.dto.ChangeRequest;
import com.moveinsync.config.server.web.dto.VersionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
@RequiredArgsConstructor
public class ChangeController {

    private static final int MAX_PAGE = 1000;

    private final ChangeSetService changeSets;
    private final ChangeRepository changes;

    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse submit(@PathVariable String tenantId, @Valid @RequestBody ChangeRequest request,
                                  @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                  Authentication auth) {
        TenantGuard.check(auth, tenantId);
        ChangeSet changeSet = request.toChangeSet(tenantId, auth.getName());
        return new VersionResponse(changeSets.submit(changeSet, ChangeKind.CHANGE, idempotencyKey));
    }

    @GetMapping("/changes")
    public List<ChangeLogEntry> history(@PathVariable String tenantId,
                                        @RequestParam(defaultValue = "0") long since,
                                        @RequestParam(defaultValue = "50") int limit, Authentication auth) {
        TenantGuard.check(auth, tenantId);
        Pageable page = Pageable.ofSize(Math.min(Math.max(limit, 1), MAX_PAGE));
        List<ChangeLogEntry> entries = new ArrayList<>();
        for (ChangeEntity change : changes.findByTenantIdAndVersionGreaterThanOrderByVersionAsc(tenantId, since, page)) {
            entries.add(new ChangeLogEntry(change.getVersion(), change.getAuthor(), change.getReason(),
                    change.getKind(), change.getCreatedAt(), change.operationsJson()));
        }
        return entries;
    }

    @DeleteMapping("/overrides/{level}/{scopeId}/{key}")
    public VersionResponse unset(@PathVariable String tenantId, @PathVariable ScopeLevel level,
                                 @PathVariable String scopeId, @PathVariable String key, Authentication auth) {
        TenantGuard.check(auth, tenantId);
        Operation unset = new Operation(OpKind.UNSET, new ScopeKey(level, scopeId), key, null);
        String reason = "remove " + key + " at " + level + " " + scopeId;
        ChangeSet changeSet = new ChangeSet(tenantId, auth.getName(), reason, List.of(unset));
        return new VersionResponse(changeSets.submit(changeSet, ChangeKind.CHANGE, null));
    }
}
