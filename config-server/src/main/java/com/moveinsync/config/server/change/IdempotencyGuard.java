package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final ChangeRepository changes;
    private final Rejections rejections;

    // same tenant + key + body -> the version stored the first time; same key with another body -> 409
    public Optional<Long> previousVersion(String tenantId, ChangeKind kind, String idempotencyKey, String requestHash) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        Optional<ChangeEntity> earlier = changes.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (earlier.isEmpty()) {
            return Optional.empty();
        }
        if (!earlier.get().getRequestHash().equals(requestHash)) {
            rejections.idempotencyConflict(kind, tenantId);
            throw new IdempotencyConflictException();
        }
        return Optional.of(earlier.get().getVersion());
    }

    // canonical body = tenant, reason and the operations written in the codec's fixed field order
    public String requestHash(ChangeSet changeSet) {
        String canonical = changeSet.tenantId() + "\n" + changeSet.reason() + "\n"
                + ChangeEventCodec.operationsToJson(changeSet.operations());
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }
}
