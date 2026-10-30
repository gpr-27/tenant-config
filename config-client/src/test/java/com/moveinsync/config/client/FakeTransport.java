package com.moveinsync.config.client;

import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.client.transport.SnapshotResponse;
import com.moveinsync.config.core.change.ChangeEvent;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

// a server the test drives by hand: queue snapshot answers, push events, drop the stream, take it down
final class FakeTransport implements ConfigTransport {

    final Deque<SnapshotResponse> answers = new ArrayDeque<>();
    final List<Long> snapshotRequests = new ArrayList<>();
    final List<Long> streamOpenedAt = new ArrayList<>();
    long serverVersion;
    boolean down;
    int calls;

    private Consumer<ChangeEvent> onEvent;
    private Runnable onResync;
    private Runnable onClose;

    @Override
    public long version() {
        call();
        return serverVersion;
    }

    // with nothing queued the server has nothing new
    @Override
    public SnapshotResponse snapshotSince(long version) {
        call();
        snapshotRequests.add(version);
        return answers.isEmpty() ? new SnapshotResponse.Delta(version, version, List.of()) : answers.poll();
    }

    @Override
    public void openStream(long lastEventId, Consumer<ChangeEvent> onEvent, Runnable onResync, Runnable onClose) {
        calls++;
        streamOpenedAt.add(lastEventId);
        if (down) {
            onClose.run();
            return;
        }
        this.onEvent = onEvent;
        this.onResync = onResync;
        this.onClose = onClose;
    }

    @Override
    public void close() {
    }

    void push(ChangeEvent event) {
        onEvent.accept(event);
    }

    void askForResync() {
        onResync.run();
    }

    void dropStream() {
        onClose.run();
    }

    private void call() {
        calls++;
        if (down) {
            throw new UncheckedIOException(new ConnectException("connection refused"));
        }
    }
}
