package com.moveinsync.config.client.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.core.snapshot.SnapshotCodec;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// reads one text/event-stream line by line: "id:" and "data:" build a frame, a blank line dispatches it, ":" lines are heartbeats
public final class SseSubscriber implements Flow.Subscriber<String> {

    private static final System.Logger log = System.getLogger(SseSubscriber.class.getName());

    private final Consumer<ChangeEvent> onEvent;
    private final Runnable onResync;
    private final Runnable onClose;
    private final long readTimeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean();

    // touched only from onNext, which the publisher never calls concurrently
    private final StringBuilder data = new StringBuilder();
    private String id;

    private volatile Flow.Subscription subscription;
    private volatile long lastLineAt = System.nanoTime();

    public SseSubscriber(Consumer<ChangeEvent> onEvent, Runnable onResync, Runnable onClose, Duration readTimeout) {
        this.onEvent = onEvent;
        this.onResync = onResync;
        this.onClose = onClose;
        this.readTimeoutNanos = readTimeout.toNanos();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        lastLineAt = System.nanoTime();
        subscription.request(Long.MAX_VALUE);
        watch(readTimeoutNanos);
    }

    @Override
    public void onNext(String line) {
        lastLineAt = System.nanoTime();
        if (line.isEmpty()) {
            String frameId = id;
            String payload = data.toString();
            id = null;
            data.setLength(0);
            if (!payload.isEmpty()) {
                dispatch(frameId, payload);
            }
        } else if (line.startsWith("id:")) {
            id = value(line, 3);
        } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            data.append(value(line, 5));
        }
        // comment lines (": ping") and "event:" carry nothing the client needs
    }

    @Override
    public void onError(Throwable error) {
        log.log(Level.INFO, "push stream failed: " + error);
        close();
    }

    @Override
    public void onComplete() {
        close();
    }

    void cancel() {
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
        close();
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    private void dispatch(String frameId, String payload) {
        ChangeEvent event = null;
        boolean resync;
        try {
            JsonNode json = SnapshotCodec.mapper().readTree(payload);
            resync = frameId == null && "resync".equals(json.path("type").asText());
            if (frameId != null) {
                event = ChangeEventCodec.fromJson(json);
            }
        } catch (IOException | RuntimeException e) {
            // we cannot tell what this frame changed, so fetch the truth instead of guessing
            log.log(Level.WARNING, "unreadable push frame id=" + frameId + ": " + e.getMessage());
            resync = true;
        }
        if (resync) {
            onResync.run();
        } else if (event != null) {
            onEvent.accept(event);
        }
    }

    private static String value(String line, int prefixLength) {
        String value = line.substring(prefixLength);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    // HttpClient has no read timeout for a streamed body, so a dead connection is noticed by silence instead
    private void watch(long delayNanos) {
        CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(this::checkIdle);
    }

    private void checkIdle() {
        if (closed.get()) {
            return;
        }
        long idle = System.nanoTime() - lastLineAt;
        if (idle >= readTimeoutNanos) {
            log.log(Level.INFO, "push stream silent for " + TimeUnit.NANOSECONDS.toSeconds(idle) + " s, dropping it");
            cancel();
        } else {
            watch(readTimeoutNanos - idle);
        }
    }
}
