package com.moveinsync.config.server.push;

import com.moveinsync.config.server.metrics.ServerMetrics;
import com.moveinsync.config.server.state.CurrentState;
import com.moveinsync.config.server.state.RecentChanges;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PushHubTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CurrentState currentState = new CurrentState();
    private final RecentChanges recentChanges = new RecentChanges(10);
    private final PushHub hub = new PushHub(recentChanges, currentState,
            new ServerMetrics(registry, currentState, recentChanges), 1);

    @AfterEach
    void closeHub() {
        hub.close();
    }

    @Test
    void subscriberBeyondTheConnectionCapIsRefused() {
        hub.subscribe(0);

        assertThatThrownBy(() -> hub.subscribe(0)).isInstanceOf(TooManyConnectionsException.class);
        assertThat(hub.connections()).isEqualTo(1);
    }

    @Test
    void queueDepthIsExportedAsAGauge() {
        assertThat(registry.get("config.push.queue.depth").gauge().value()).isZero();
    }

    @Test
    void firstFrameIsAConnectedCommentSoTheResponseStartsAtOnce() {
        StubEmitter emitter = new StubEmitter(null);

        hub.subscribe(0, emitter);

        await().atMost(Duration.ofSeconds(2)).until(() -> !emitter.written.isEmpty());
        assertThat(emitter.written.get(0)).isEqualTo(": connected\n\n");
    }

    @Test
    void clientThatHungUpIsDroppedWithoutCountingASendFailure() {
        hub.subscribe(0, new StubEmitter(new IOException("Broken pipe")));

        await().atMost(Duration.ofSeconds(2)).until(() -> hub.connections() == 0);
        assertThat(sendFailures()).isZero();
    }

    @Test
    void responseAlreadyUnusableAfterADisconnectIsNotASendFailure() {
        hub.subscribe(0, new StubEmitter(new AsyncRequestNotUsableException("Response not usable after errors")));

        await().atMost(Duration.ofSeconds(2)).until(() -> hub.connections() == 0);
        assertThat(sendFailures()).isZero();
    }

    @Test
    void writeThatFailsForAnotherReasonIsCountedAndDropsTheConnection() {
        hub.subscribe(0, new StubEmitter(new IllegalStateException("Failed to send frame")));

        await().atMost(Duration.ofSeconds(2)).until(() -> hub.connections() == 0);
        assertThat(sendFailures()).isEqualTo(1);
    }

    @Test
    void disconnectReportedByTheContainerDropsTheConnectionAtOnce() {
        StubEmitter emitter = new StubEmitter(null);
        hub.subscribe(0, emitter);

        emitter.containerReportsError(new IOException("Connection reset by peer"));

        assertThat(hub.connections()).isZero();
        assertThat(sendFailures()).isZero();
    }

    private double sendFailures() {
        return registry.get("config.push.send.failures").counter().count();
    }

    // stands in for the servlet response: records what was written, or fails every write the given way
    private static final class StubEmitter extends SseEmitter {

        final List<Object> written = new CopyOnWriteArrayList<>();
        private final Exception failure;
        private Consumer<Throwable> onError;

        StubEmitter(Exception failure) {
            super(0L);
            this.failure = failure;
        }

        @Override
        public void send(Set<DataWithMediaType> frame) throws IOException {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            for (DataWithMediaType part : frame) {
                written.add(part.getData());
            }
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            this.onError = callback;
            super.onError(callback);
        }

        void containerReportsError(Throwable error) {
            onError.accept(error);
        }
    }
}
