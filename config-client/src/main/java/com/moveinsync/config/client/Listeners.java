package com.moveinsync.config.client;

import com.moveinsync.config.core.snapshot.Snapshot;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// the application's listeners, told about each new snapshot in version order
final class Listeners {

    private static final System.Logger log = System.getLogger(Listeners.class.getName());

    private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<>();

    void add(SnapshotListener listener) {
        listeners.add(listener);
    }

    void tell(Snapshot snapshot) {
        for (SnapshotListener listener : listeners) {
            try {
                listener.onSnapshot(snapshot);
            } catch (RuntimeException e) {
                // one broken listener must not stop the others or the update itself
                log.log(Level.WARNING, "snapshot listener failed on version " + snapshot.version() + ": " + e);
            }
        }
    }
}
