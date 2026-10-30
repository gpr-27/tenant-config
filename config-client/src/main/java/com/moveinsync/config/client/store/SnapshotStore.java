package com.moveinsync.config.client.store;

import com.moveinsync.config.core.snapshot.Snapshot;

import java.util.Optional;

public interface SnapshotStore {

    // empty when there is nothing usable on disk
    Optional<Snapshot> load();

    // throws UncheckedIOException on failure; the caller logs it and keeps serving from memory
    void save(Snapshot snapshot);
}
