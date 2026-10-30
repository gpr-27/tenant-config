package com.moveinsync.config.client.store;

import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.Optional;

// for clients that would rather start empty than read a disk copy
public final class NoopSnapshotStore implements SnapshotStore {

    @Override
    public Optional<Snapshot> load() {
        return Optional.empty();
    }

    @Override
    public void save(Snapshot snapshot) {
    }
}
