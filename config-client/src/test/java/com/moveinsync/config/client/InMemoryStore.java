package com.moveinsync.config.client;

import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.Optional;

final class InMemoryStore implements SnapshotStore {

    Snapshot saved;

    @Override
    public Optional<Snapshot> load() {
        return Optional.ofNullable(saved);
    }

    @Override
    public void save(Snapshot snapshot) {
        saved = snapshot;
    }
}
