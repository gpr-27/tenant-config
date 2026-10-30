package com.moveinsync.config.server.state;

import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

// the server's own copy of the snapshot; only ChangeSetService (under its lock) and StateLoader write it
@Component
public class CurrentState {

    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    public Snapshot snapshot() {
        return current.get();
    }

    public void apply(long version, ChangeSet changeSet) {
        current.set(current.get().apply(version, changeSet));
    }

    public void applyDefinition(long version, SettingDefinition definition) {
        current.set(current.get().withDefinition(version, definition));
    }

    public void replace(Snapshot snapshot) {
        current.set(snapshot);
    }
}
