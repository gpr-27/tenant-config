package com.moveinsync.config.server.push;

import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import com.moveinsync.config.server.metrics.ServerMetrics;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PushHub {

    // SseEmitter.send is a blocking write: one single-thread executor per stripe means a slow client only delays
    // the connections that share its stripe, and every connection still gets its events in version order.
    // Built as ThreadPoolExecutor rather than Executors.newSingleThreadExecutor so the queue depth can be read.
    private static final int STRIPES = 8;

    private final Map<UUID, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor[] stripes = new ThreadPoolExecutor[STRIPES];
    // held by subscribe and by the enqueue phase of publish, so no event slips between "replay since V" and "live"
    private final Object registration = new Object();
    private final RecentChanges recentChanges;
    private final CurrentState currentState;
    private final ServerMetrics metrics;
    private final int maxConnections;

    public PushHub(RecentChanges recentChanges, CurrentState currentState, ServerMetrics metrics,
                   @Value("${config.push.maxConnections}") int maxConnections) {
        this.recentChanges = recentChanges;
        this.currentState = currentState;
        this.metrics = metrics;
        this.maxConnections = maxConnections;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                    Thread.ofPlatform().name("push-" + i).daemon().factory());
        }
        metrics.trackConnections(emitters);
        metrics.trackPushQueue(stripes);
    }

    public SseEmitter subscribe(long lastVersion) {
        UUID id = UUID.randomUUID();
        // no server-side timeout: a dead connection is found by the next failed write (heartbeat at the latest)
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> remove(id));
        emitter.onTimeout(() -> remove(id));
        emitter.onError(error -> remove(id));

        boolean resync;
        synchronized (registration) {
            // checked under the same lock as the put, so concurrent subscribes cannot all pass a size of max - 1
            if (emitters.size() >= maxConnections) {
                throw new TooManyConnectionsException();
            }
            emitters.put(id, emitter);
            Optional<List<ChangeEvent>> missed = recentChanges.since(lastVersion);
            // a client ahead of us saw a version this server no longer has (restored database): it must resync too
            resync = missed.isEmpty() || lastVersion > currentState.snapshot().version();
            if (resync) {
                enqueue(id, emitter, resyncFrame());
            } else {
                for (ChangeEvent event : missed.get()) {
                    enqueue(id, emitter, changeFrame(event));
                }
            }
        }
        log.info("push client connected id={} lastVersion={} connections={}", id, lastVersion, emitters.size());
        if (resync) {
            log.warn("push client {} cannot be caught up from memory (lastVersion={}), sent resync", id, lastVersion);
        }
        return emitter;
    }

    public void publish(ChangeEvent event) {
        Set<DataWithMediaType> frame = changeFrame(event);
        synchronized (registration) {
            for (Map.Entry<UUID, SseEmitter> entry : emitters.entrySet()) {
                enqueue(entry.getKey(), entry.getValue(), frame);
            }
        }
    }

    // @Scheduled in Spring 6.1 reads only millis or ISO-8601, so the "15s" from application.yml is converted here
    @Scheduled(fixedRateString = "#{T(org.springframework.boot.convert.DurationStyle)"
            + ".detectAndParse('${config.push.heartbeat}').toMillis()}")
    public void heartbeat() {
        Set<DataWithMediaType> ping = SseEmitter.event().comment(" ping").build();
        for (Map.Entry<UUID, SseEmitter> entry : emitters.entrySet()) {
            enqueue(entry.getKey(), entry.getValue(), ping);
        }
    }

    public int connections() {
        return emitters.size();
    }

    @PreDestroy
    void close() {
        for (SseEmitter emitter : emitters.values()) {
            emitter.complete();
        }
        for (ThreadPoolExecutor stripe : stripes) {
            stripe.shutdown();
        }
    }

    private void enqueue(UUID id, SseEmitter emitter, Set<DataWithMediaType> frame) {
        stripes[Math.floorMod(id.hashCode(), STRIPES)].execute(() -> send(id, emitter, frame));
    }

    private void send(UUID id, SseEmitter emitter, Set<DataWithMediaType> frame) {
        try {
            emitter.send(frame);
        } catch (IOException | IllegalStateException e) {
            // the container reports the broken connection itself; we only stop writing to it
            if (remove(id)) {
                metrics.pushSendFailed();
            }
        }
    }

    private boolean remove(UUID id) {
        if (emitters.remove(id) == null) {
            return false;
        }
        log.info("push client disconnected id={} connections={}", id, emitters.size());
        return true;
    }

    // the spaces after the colons give the frame exactly as specified ("id: 144"); SSE readers drop one space
    private static Set<DataWithMediaType> changeFrame(ChangeEvent event) {
        return SseEmitter.event()
                .id(" " + event.version())
                .name(" change")
                .data(" " + ChangeEventCodec.toJson(event))
                .build();
    }

    private static Set<DataWithMediaType> resyncFrame() {
        return SseEmitter.event().data(" {\"type\":\"resync\"}").build();
    }
}
