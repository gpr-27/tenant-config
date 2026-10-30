package com.moveinsync.config.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// nothing runs until the test says so, and nothing sleeps: runPeriodic() is one heal-poll tick, runDelayed() fires reconnects
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

    final List<Long> delaysMillis = new ArrayList<>();
    private final List<Runnable> periodic = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();

    void runPeriodic() {
        for (Runnable task : List.copyOf(periodic)) {
            task.run();
        }
    }

    void runDelayed() {
        List<Runnable> due = List.copyOf(delayed);
        delayed.clear();
        for (Runnable task : due) {
            task.run();
        }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        delaysMillis.add(unit.toMillis(delay));
        delayed.add(command);
        return new Handle(delayed, command);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        periodic.add(command);
        return new Handle(periodic, command);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    private record Handle(List<Runnable> queue, Runnable task) implements ScheduledFuture<Object> {

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return queue.remove(task);
        }

        @Override
        public boolean isCancelled() {
            return !queue.contains(task);
        }

        @Override
        public boolean isDone() {
            return isCancelled();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
