package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoadedHealthTest {

    @Test
    void downAtVersionZeroAndUpOnceTheFirstSnapshotIsIn() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        try (ConfigClient client = BookingControllerTest.clientServing(BookingControllerTest.officeCutoffAt7(), exec)) {
            ConfigLoadedHealth health = new ConfigLoadedHealth(client);

            Health before = health.health();
            assertThat(before.getStatus()).isEqualTo(Status.DOWN);
            assertThat(before.getDetails()).containsEntry("version", 0L);

            client.start();

            Health after = health.health();
            assertThat(after.getStatus()).isEqualTo(Status.UP);
            assertThat(after.getDetails()).containsEntry("version", 7L);
        } finally {
            exec.shutdownNow();
        }
    }
}
