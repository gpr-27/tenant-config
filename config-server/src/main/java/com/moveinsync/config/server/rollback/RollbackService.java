package com.moveinsync.config.server.rollback;

import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.server.change.ChangeSetService;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.web.dto.RollbackResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

// a rollback is a new version whose content equals an old one: history only moves forward
@Service
@RequiredArgsConstructor
public class RollbackService {

    private final StateReplayer replayer;
    private final CurrentState currentState;
    private final ChangeSetService changeSets;

    public RollbackResponse rollback(String tenantId, long targetVersion, boolean dryRun, String author) {
        Snapshot current = currentState.snapshot();
        if (targetVersion < 0 || targetVersion > current.version()) {
            throw new VersionNotFoundException(targetVersion);
        }
        List<Operation> diff = Diff.between(current.tenantOverrides(tenantId),
                replayer.stateAt(tenantId, targetVersion), current.definitions());
        if (dryRun || diff.isEmpty()) {
            return new RollbackResponse(targetVersion, ChangeEventCodec.operationsToJson(diff), null);
        }

        // submitted like any change, so a value that today's rules reject makes the rollback a 422
        ChangeSet changeSet = new ChangeSet(tenantId, author, "rollback to #" + targetVersion, diff);
        long version = changeSets.submit(changeSet, ChangeKind.ROLLBACK, null);
        return new RollbackResponse(targetVersion, ChangeEventCodec.operationsToJson(diff), version);
    }
}
