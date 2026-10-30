package com.moveinsync.config.client;

import com.moveinsync.config.client.sync.Backoff;
import com.moveinsync.config.client.transport.SnapshotResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static com.moveinsync.config.client.Fixtures.cutoff;
import static com.moveinsync.config.client.Fixtures.snapshotAt;
import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    private static final Duration BASE = Duration.ofMillis(500);
    private static final Duration CAP = Duration.ofSeconds(10);

    // always draws the top of the window, so each delay shows the window itself
    private static final Random TOP_OF_WINDOW = new Random() {
        @Override
        public long nextLong(long origin, long bound) {
            return bound - 1;
        }
    };

    @Test
    void delaysStayWithinTheJitterWindow() {
        Backoff backoff = new Backoff(BASE, CAP, new Random(42));

        for (int attempt = 0; attempt < 20; attempt++) {
            long ceiling = Math.min(CAP.toMillis(), BASE.toMillis() << attempt);
            assertThat(backoff.nextDelay().toMillis()).isBetween(0L, ceiling);
        }
    }

    @Test
    void windowDoublesUntilTheCap() {
        Backoff backoff = new Backoff(BASE, CAP, TOP_OF_WINDOW);

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(1000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(2000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(4000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(8000));
        for (int attempt = 5; attempt < 100; attempt++) {
            assertThat(backoff.nextDelay()).isEqualTo(CAP);
        }
    }

    @Test
    void resetAfterSuccessStartsAgainFromBase() {
        Backoff backoff = new Backoff(BASE, CAP, TOP_OF_WINDOW);
        backoff.nextDelay();
        backoff.nextDelay();
        backoff.nextDelay();

        backoff.reset();

        assertThat(backoff.nextDelay()).isEqualTo(BASE);
    }

    @Test
    void clientResetsBackoffOnTheNextEventAndReconnectsFromLocal() {
        FakeTransport transport = new FakeTransport();
        transport.answers.add(new SnapshotResponse.Full(snapshotAt(43)));
        ManualScheduler scheduler = new ManualScheduler();
        CountingMetrics metrics = new CountingMetrics();
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(), metrics, scheduler,
                new Backoff(BASE, CAP, TOP_OF_WINDOW), false);
        client.start();

        transport.dropStream();
        scheduler.runDelayed();
        transport.dropStream();
        scheduler.runDelayed();
        transport.push(cutoff(44, "PT30M"));
        transport.dropStream();
        scheduler.runDelayed();

        assertThat(scheduler.delaysMillis).containsExactly(500L, 1000L, 500L);
        assertThat(transport.streamOpenedAt).containsExactly(43L, 43L, 43L, 44L);
        assertThat(metrics.reconnects).isEqualTo(3);
    }

    @Test
    void closedClientDoesNotReconnect() {
        FakeTransport transport = new FakeTransport();
        ManualScheduler scheduler = new ManualScheduler();
        ConfigClient client = new ConfigClient(Fixtures.options(), transport, new InMemoryStore(),
                new CountingMetrics(), scheduler);
        client.start();

        client.close();
        transport.dropStream();

        assertThat(scheduler.delaysMillis).isEmpty();
    }
}
