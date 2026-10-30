package com.moveinsync.config.client.transport;

import com.moveinsync.config.client.Fixtures;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeEventCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SseSubscriberTest {

    private final List<ChangeEvent> events = new ArrayList<>();
    private int resyncs;
    private int closes;
    private final SseSubscriber subscriber = new SseSubscriber(events::add, () -> resyncs++, () -> closes++,
            Duration.ofMinutes(5));

    @Test
    void changeFrameIsDispatchedAtTheBlankLine() {
        String json = ChangeEventCodec.toJson(Fixtures.cutoff(144, "PT30M")).toString();

        feed("id: 144", "event: change", "data: " + json);
        assertThat(events).isEmpty();

        feed("");
        assertThat(events).extracting(ChangeEvent::version).containsExactly(144L);
        assertThat(resyncs).isZero();
    }

    @Test
    void heartbeatCommentsAreIgnored() {
        feed(": ping", "", ": ping", "");

        assertThat(events).isEmpty();
        assertThat(resyncs).isZero();
    }

    @Test
    void resyncFrameWithoutIdAsksForResync() {
        feed("data: {\"type\":\"resync\"}", "");

        assertThat(resyncs).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    void framesAreDispatchedOneByOneInOrder() {
        feed("id: 144", "data: " + ChangeEventCodec.toJson(Fixtures.cutoff(144, "PT30M")), "",
                ": ping", "",
                "id: 145", "data: " + ChangeEventCodec.toJson(Fixtures.cutoff(145, "PT20M")), "");

        assertThat(events).extracting(ChangeEvent::version).containsExactly(144L, 145L);
    }

    @Test
    void unreadableFrameAsksForResync() {
        feed("id: 144", "data: {not json", "");

        assertThat(events).isEmpty();
        assertThat(resyncs).isEqualTo(1);
    }

    @Test
    void endOfStreamClosesExactlyOnce() {
        subscriber.onComplete();
        subscriber.onError(new IOException("connection reset"));
        subscriber.cancel();

        assertThat(closes).isEqualTo(1);
    }

    private void feed(String... lines) {
        for (String line : lines) {
            subscriber.onNext(line);
        }
    }
}
