package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@Import(CanBookBeforeFirstSnapshotTest.EmptyServer.class)
class CanBookBeforeFirstSnapshotTest {

    @Autowired
    MockMvc mvc;

    @Test
    void answers503WithTheReasonInTheProblemDetail() throws Exception {
        mvc.perform(get("/bookings/can-book?tenantId=acme&officeId=blr&departureInMinutes=60"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("configuration not loaded yet"));
    }

    @TestConfiguration
    static class EmptyServer {

        @Bean(destroyMethod = "shutdownNow")
        ScheduledExecutorService configClientScheduler() {
            return Executors.newSingleThreadScheduledExecutor();
        }

        // a server with nothing committed yet, and no disk copy: the client stays at version 0
        @Bean(destroyMethod = "close")
        ConfigClient configClient(ScheduledExecutorService exec) {
            ConfigClient client = BookingControllerTest.clientServing(Snapshot.empty(), exec);
            client.start();
            return client;
        }
    }
}
