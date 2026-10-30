package com.moveinsync.config.client.transport;

import com.moveinsync.config.core.change.ChangeEvent;

import java.util.function.Consumer;

// the network seam: ConfigClient holds the rules, this only moves bytes. Failures are UncheckedIOException.
public interface ConfigTransport extends AutoCloseable {

    long version();

    SnapshotResponse snapshotSince(long version);

    // returns at once; onAlive runs for every heartbeat or frame the server sends; onClose runs exactly once when
    // the stream ends, fails or cannot be opened
    void openStream(long lastEventId, Consumer<ChangeEvent> onEvent, Runnable onResync, Runnable onAlive,
                    Runnable onClose);

    @Override
    void close();
}
