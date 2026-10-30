package com.moveinsync.config.client.transport;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.List;

// the answer to GET /config/snapshot?since=V: the missing changes if the server still has them, else everything
public sealed interface SnapshotResponse {

    record Delta(long from, long to, List<ChangeEvent> changes) implements SnapshotResponse {

        public Delta {
            changes = List.copyOf(changes);
        }
    }

    record Full(Snapshot snapshot) implements SnapshotResponse {
    }
}
