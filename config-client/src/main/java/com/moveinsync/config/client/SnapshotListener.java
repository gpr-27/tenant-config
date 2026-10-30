package com.moveinsync.config.client;

import com.moveinsync.config.core.snapshot.Snapshot;

public interface SnapshotListener {

    void onSnapshot(Snapshot snapshot);
}
