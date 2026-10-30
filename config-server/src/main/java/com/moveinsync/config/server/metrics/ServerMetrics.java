package com.moveinsync.config.server.metrics;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

// dotted names; the Prometheus registry exports them as config_server_version, config_changes_total, ...
@Component
public class ServerMetrics {

    private final MeterRegistry registry;
    private final Counter pushSendFailures;
    private final Timer resolveLatency;

    public ServerMetrics(MeterRegistry registry, CurrentState currentState, RecentChanges recentChanges) {
        this.registry = registry;
        Gauge.builder("config.server.version", currentState, state -> state.snapshot().version())
                .description("version of the server's current snapshot")
                .register(registry);
        Gauge.builder("config.recent.changes.size", recentChanges, RecentChanges::size)
                .description("events held for delta catch-up")
                .register(registry);
        this.pushSendFailures = Counter.builder("config.push.send.failures")
                .description("SSE writes that failed and dropped their connection")
                .register(registry);
        this.resolveLatency = Timer.builder("config.resolve.latency")
                .description("time to resolve one key through the REST API")
                .register(registry);
        // registered up front so every series is in /actuator/prometheus before the first change arrives
        for (ChangeKind kind : ChangeKind.values()) {
            changes(kind, "accepted");
            changes(kind, "rejected");
        }
    }

    // PushHub owns the connection map, so it hands it over instead of this class depending on PushHub
    public void trackConnections(Map<?, ?> connections) {
        Gauge.builder("config.push.connections", connections, Map::size)
                .description("open SSE connections")
                .register(registry);
    }

    // the stripes' queues hold frames accepted for sending but not yet written to a socket
    public void trackPushQueue(ThreadPoolExecutor[] stripes) {
        Gauge.builder("config.push.queue.depth", stripes, ServerMetrics::queued)
                .description("SSE frames waiting to be written")
                .register(registry);
    }

    public void changeAccepted(ChangeKind kind) {
        changes(kind, "accepted").increment();
    }

    public void changeRejected(ChangeKind kind, List<ValidationError> errors) {
        changes(kind, "rejected").increment();
        for (ValidationError error : errors) {
            registry.counter("config.validation.errors", "code", error.code()).increment();
        }
    }

    public void pushSendFailed() {
        pushSendFailures.increment();
    }

    public <T> T timeResolve(Supplier<T> resolve) {
        return resolveLatency.record(resolve);
    }

    private Counter changes(ChangeKind kind, String result) {
        return registry.counter("config.changes", "kind", kind.name(), "result", result);
    }

    private static int queued(ThreadPoolExecutor[] stripes) {
        int total = 0;
        for (ThreadPoolExecutor stripe : stripes) {
            total += stripe.getQueue().size();
        }
        return total;
    }
}
