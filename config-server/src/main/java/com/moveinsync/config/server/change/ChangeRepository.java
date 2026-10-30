package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.definition.SettingDefinition;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChangeRepository extends JpaRepository<ChangeEntity, Long> {

    // INSERT ... with version = nextval('config_version_seq'); the caller holds the write lock
    default long append(ChangeSet changeSet, ChangeKind kind, String idempotencyKey, String requestHash, Instant at) {
        return save(ChangeEntity.of(changeSet, kind, idempotencyKey, requestHash, at)).getVersion();
    }

    default long appendDefinition(SettingDefinition definition, String author, String reason, Instant at) {
        return save(ChangeEntity.ofDefinition(definition, author, reason, at)).getVersion();
    }

    @Query("select coalesce(max(c.version), 0) from ChangeEntity c")
    long maxVersion();

    // next nextval returns version + 1 (setval(seq, 1, false) when the log is empty)
    @Transactional
    @Query(value = "select setval('config_version_seq', greatest(:version, 1), :version > 0)", nativeQuery = true)
    long rewindSequence(long version);

    Optional<ChangeEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);

    List<ChangeEntity> findByTenantIdAndVersionGreaterThanOrderByVersionAsc(String tenantId, long since, Pageable page);

    List<ChangeEntity> findByTenantIdAndVersionLessThanEqualOrderByVersionAsc(String tenantId, long version);

    List<ChangeEntity> findByOrderByVersionDesc(Pageable page);
}
