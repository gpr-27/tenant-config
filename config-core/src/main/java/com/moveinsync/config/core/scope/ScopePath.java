package com.moveinsync.config.core.scope;

import java.util.ArrayList;
import java.util.List;

public record ScopePath(String tenantId, String officeId, String shiftId) {

    // GLOBAL is never in the list: it is the definition default, the fallback after the walk
    public List<ScopeKey> fromMostSpecific() {
        List<ScopeKey> keys = new ArrayList<>(3);
        if (shiftId != null) {
            keys.add(ScopeKey.shift(shiftId));
        }
        if (officeId != null) {
            keys.add(ScopeKey.office(officeId));
        }
        if (tenantId != null) {
            keys.add(ScopeKey.client(tenantId));
        }
        return keys;
    }
}
