package com.moveinsync.config.client;

import com.moveinsync.config.client.metrics.ClientMetrics;
import com.moveinsync.config.client.metrics.NoopClientMetrics;
import com.moveinsync.config.client.store.FileSnapshotStore;
import com.moveinsync.config.client.store.SnapshotStore;
import com.moveinsync.config.client.sync.Backoff;
import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.client.transport.HttpConfigTransport;
import com.moveinsync.config.client.transport.SnapshotResponse;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Resolved;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// reads are one volatile load; every write goes through advance(), which never holds its lock across network I/O
public final class ConfigClient implements AutoCloseable {

    private static final System.Logger log = System.getLogger(ConfigClient.class.getName());

    // a server that keeps answering with a broken delta must not spin us; the next heal poll tries again
    private static final int MAX_RESYNC_ROUNDS = 3;

    private enum Advance { APPLIED, STALE, GAP }

    private final ConfigClientOptions options;
    private final ConfigTransport transport;
    private final SnapshotStore store;
    private final ClientMetrics metrics;
    private final ScheduledExecutorService exec;
    private final boolean ownsExec;
    private final Backoff backoff;
    private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Snapshot current = Snapshot.empty();
    private volatile boolean closed;
    private volatile ScheduledFuture<?> healPollTask;

    public ConfigClient(ConfigClientOptions options) {
        this(options, new HttpConfigTransport(options), new FileSnapshotStore(options.snapshotFile()),
                new NoopClientMetrics(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("config-client").daemon().factory()),
                newBackoff(options), true);
    }

    // the seam for tests and for apps that bring their own metrics or scheduler; the scheduler stays theirs to shut down
    public ConfigClient(ConfigClientOptions options, ConfigTransport transport, SnapshotStore store,
                        ClientMetrics metrics, ScheduledExecutorService exec) {
        this(options, transport, store, metrics, exec, newBackoff(options), false);
    }

    // tests pass a Backoff with a fixed Random so reconnect delays are predictable
    ConfigClient(ConfigClientOptions options, ConfigTransport transport, SnapshotStore store,
                 ClientMetrics metrics, ScheduledExecutorService exec, Backoff backoff, boolean ownsExec) {
        this.options = options;
        this.transport = transport;
        this.store = store;
        this.metrics = metrics;
        this.exec = exec;
        this.backoff = backoff;
        this.ownsExec = ownsExec;
    }

    public void start() {
        store.load().ifPresent(this::advance);
        resync();
        openStream();
        long poll = options.healPollInterval().toMillis();
        healPollTask = exec.scheduleAtFixedRate(this::healPoll, poll, poll, TimeUnit.MILLISECONDS);
    }

    // take one snapshot and read every related key from it to see them consistently
    public Snapshot snapshot() {
        return current;
    }

    public <T> T get(String tenantId, String key, ScopePath path, Class<T> type) {
        long start = System.nanoTime();
        T value = current.get(tenantId, key, path, type);
        metrics.readTimer(System.nanoTime() - start);
        return value;
    }

    public Resolved explain(String tenantId, String key, ScopePath path) {
        long start = System.nanoTime();
        Resolved resolved = current.resolve(tenantId, key, path);
        metrics.readTimer(System.nanoTime() - start);
        return resolved;
    }

    public long version() {
        return current.version();
    }

    public void addListener(SnapshotListener listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> task = healPollTask;
        if (task != null) {
            task.cancel(false);
        }
        transport.close();
        if (ownsExec) {
            exec.shutdownNow();
        }
    }

    private void openStream() {
        if (!closed) {
            transport.openStream(current.version(), this::onEvent, this::onResyncRequested, this::onStreamClosed);
        }
    }

    private void onEvent(ChangeEvent event) {
        backoff.reset();
        switch (advance(List.of(event))) {
            case APPLIED -> metrics.eventAge(Duration.between(event.createdAt(), Instant.now()));
            case STALE -> metrics.staleEvent();
            case GAP -> {
                metrics.gapResync();
                log.log(Level.WARNING, "push " + event.version() + " does not follow local " + current.version() + ", resyncing");
                resync();
            }
        }
    }

    private void onResyncRequested() {
        backoff.reset();
        log.log(Level.WARNING, "server asked for a resync at local " + current.version());
        resync();
    }

    private void onStreamClosed() {
        if (closed) {
            return;
        }
        long delay = backoff.nextDelay().toMillis();
        metrics.reconnect();
        log.log(Level.INFO, "push stream closed at version " + current.version() + ", reconnecting in " + delay + " ms");
        exec.schedule(this::openStream, delay, TimeUnit.MILLISECONDS);
    }

    private void healPoll() {
        try {
            long server = transport.version();
            long local = current.version();
            metrics.lag(server - local);
            if (server > local) {
                log.log(Level.WARNING, "heal poll: server at " + server + ", local at " + local + ", resyncing");
                resync();
            }
        } catch (RuntimeException e) {
            // an exception would cancel the fixed-rate schedule for good; the next tick simply asks again
            log.log(Level.WARNING, "heal poll failed: " + e);
        }
    }

    private void resync() {
        try {
            for (int round = 0; round < MAX_RESYNC_ROUNDS; round++) {
                boolean done = switch (transport.snapshotSince(current.version())) {
                    case SnapshotResponse.Full full -> {
                        advance(full.snapshot());
                        yield true;
                    }
                    case SnapshotResponse.Delta delta -> advance(delta.changes()) != Advance.GAP;
                };
                if (done) {
                    return;
                }
            }
            log.log(Level.WARNING, "delta still has a gap after " + MAX_RESYNC_ROUNDS + " tries, waiting for the heal poll");
        } catch (RuntimeException e) {
            // server down or a bad answer: keep serving what we have, the heal poll retries
            log.log(Level.WARNING, "resync failed at version " + current.version() + ": " + e);
        }
    }

    // applies the run of changes that follows local exactly; anything at or below local is already in it
    private synchronized Advance advance(List<ChangeEvent> changes) {
        Snapshot next = current;
        Advance outcome = Advance.STALE;
        for (ChangeEvent change : changes) {
            if (change.version() <= next.version()) {
                continue;
            }
            if (change.version() != next.version() + 1) {
                outcome = Advance.GAP;
                break;
            }
            next = next.apply(change);
            outcome = Advance.APPLIED;
        }
        if (next != current) {
            replace(next);
        }
        return outcome;
    }

    private synchronized void advance(Snapshot full) {
        if (full.version() < current.version()) {
            log.log(Level.WARNING, "server snapshot " + full.version() + " is older than local " + current.version()
                    + ", accepting it: the server is the truth (restored from a backup?)");
        }
        replace(full);
    }

    // only called under advance's lock, so disk writes and listener calls happen in version order
    private void replace(Snapshot next) {
        current = next;
        try {
            store.save(next);
        } catch (RuntimeException e) {
            // the disk copy only matters for a cold start; a failed write must not hold back the update itself
            log.log(Level.WARNING, "could not save snapshot " + next.version() + " to disk: " + e.getMessage());
        }
        for (SnapshotListener listener : listeners) {
            try {
                listener.onSnapshot(next);
            } catch (RuntimeException e) {
                // one broken listener must not stop the others or the update itself
                log.log(Level.WARNING, "snapshot listener failed on version " + next.version() + ": " + e);
            }
        }
        metrics.version(next.version());
    }

    private static Backoff newBackoff(ConfigClientOptions options) {
        return new Backoff(options.backoffBase(), options.backoffCap(), new Random());
    }
}
