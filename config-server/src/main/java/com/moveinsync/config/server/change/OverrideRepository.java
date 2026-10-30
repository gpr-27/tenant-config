package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OverrideRepository extends JpaRepository<OverrideEntity, OverrideEntity.Id> {

    // SET upserts, UNSET deletes; runs inside the caller's transaction next to the log insert
    default void applyAll(String tenantId, List<Operation> operations, long version) {
        for (Operation op : operations) {
            OverrideEntity.Id id = OverrideEntity.Id.of(tenantId, op.scope(), op.key());
            if (op.op() == OpKind.SET) {
                save(new OverrideEntity(id, op.value(), version));
            } else {
                deleteById(id);
            }
        }
    }
}
