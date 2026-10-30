package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.client.ConfigClientOptions;
import com.moveinsync.config.client.metrics.NoopClientMetrics;
import com.moveinsync.config.client.store.NoopSnapshotStore;
import com.moveinsync.config.client.transport.ConfigTransport;
import com.moveinsync.config.client.transport.SnapshotResponse;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@Import(BookingControllerTest.FakeServer.class)
class BookingControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void departureExactlyAtOfficeCutoffIsAllowed() throws Exception {
        mvc.perform(get("/bookings/can-book?tenantId=acme&officeId=blr&departureInMinutes=60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.cutoff").value("PT1H"))
                .andExpect(jsonPath("$.configVersion").value(7));
    }

    @Test
    void departureOneMinuteInsideOfficeCutoffIsRefused() throws Exception {
        mvc.perform(get("/bookings/can-book?tenantId=acme&officeId=blr&departureInMinutes=59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.cutoff").value("PT1H"));
    }

    @TestConfiguration
    static class FakeServer {

        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService configClientScheduler() {
            return Executors.newSingleThreadScheduledExecutor();
        }

        @Bean(destroyMethod = "close")
        ConfigClient configClient(ScheduledExecutorService exec) {
            SettingDefinition cutoff = new SettingDefinition("booking.cutoff", SettingTypes.DURATION, Rules.NONE,
                    Duration.ofMinutes(30), "how long before departure booking closes");
            Snapshot snapshot = Snapshot.of(7, Map.of("booking.cutoff", cutoff),
                    Map.of("acme", Map.of(ScopeKey.office("blr"), Map.of("booking.cutoff", Duration.ofMinutes(60)))));

            ConfigClientOptions options = ConfigClientOptions.defaults(URI.create("http://unused"), () -> "token",
                    Path.of("unused"));
            ConfigClient client = new ConfigClient(options, new FixedSnapshotTransport(snapshot),
                    new NoopSnapshotStore(), new NoopClientMetrics(), exec);
            client.start();
            return client;
        }
    }

    // a server that always answers with the same full snapshot and never pushes
    record FixedSnapshotTransport(Snapshot snapshot) implements ConfigTransport {

        @Override
        public long version() {
            return snapshot.version();
        }

        @Override
        public SnapshotResponse snapshotSince(long version) {
            return new SnapshotResponse.Full(snapshot);
        }

        @Override
        public void openStream(long lastEventId, Consumer<ChangeEvent> onEvent, Runnable onResync, Runnable onClose) {
        }

        @Override
        public void close() {
        }
    }
}
