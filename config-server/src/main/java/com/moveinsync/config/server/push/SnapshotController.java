package com.moveinsync.config.server.push;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.SnapshotCodec;
import com.moveinsync.config.server.security.TenantGuard;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import com.moveinsync.config.server.web.dto.VersionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class SnapshotController {

    private final CurrentState currentState;
    private final RecentChanges recentChanges;

    @GetMapping("/version")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    public VersionResponse version() {
        return new VersionResponse(currentState.snapshot().version());
    }

    // delta when the ring still covers `since`, otherwise the whole state; no `since` asks for the whole state
    @GetMapping("/snapshot")
    @PreAuthorize("isAuthenticated()")
    public ObjectNode snapshot(@RequestParam(required = false) Long since, Authentication auth) {
        String onlyTenant = TenantGuard.seesAllTenants(auth) ? null : TenantGuard.tenantOf(auth);
        Snapshot snapshot = currentState.snapshot();
        if (since != null && since <= snapshot.version()) {
            Optional<List<ChangeEvent>> missed = recentChanges.since(since);
            if (missed.isPresent()) {
                return delta(since, missed.get(), onlyTenant);
            }
        }
        return full(snapshot, onlyTenant);
    }

    // `to` is the newest event read from the ring, not the snapshot version: the ring is filled just after
    // the snapshot is swapped, and promising a version whose event is not in the list would lose that event
    private static ObjectNode delta(long since, List<ChangeEvent> events, String onlyTenant) {
        ObjectNode json = SnapshotCodec.mapper().createObjectNode();
        json.put("type", "delta");
        json.put("from", since);
        json.put("to", events.isEmpty() ? since : events.get(events.size() - 1).version());
        ArrayNode changes = json.putArray("changes");
        for (ChangeEvent event : events) {
            if (visibleTo(event, onlyTenant)) {
                changes.add(ChangeEventCodec.toJson(event));
            }
        }
        return json;
    }

    // other tenants' events are dropped without renumbering, so a tenant-scoped delta has gaps by design
    private static boolean visibleTo(ChangeEvent event, String onlyTenant) {
        if (onlyTenant == null || !(event.payload() instanceof OverridesChanged changed)) {
            return true;
        }
        return changed.changeSet().tenantId().equals(onlyTenant);
    }

    private static ObjectNode full(Snapshot snapshot, String onlyTenant) {
        Snapshot visible = onlyTenant == null ? snapshot
                : Snapshot.of(snapshot.version(), snapshot.definitions(),
                        Map.of(onlyTenant, snapshot.tenantOverrides(onlyTenant)));
        ObjectNode json = SnapshotCodec.mapper().createObjectNode();
        json.put("type", "full");
        json.setAll(SnapshotCodec.toJson(visible));
        return json;
    }
}
