package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Resolved;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class BookingController {

    private final ConfigClient client;

    public BookingController(ConfigClient client) {
        this.client = client;
    }

    @GetMapping("/bookings/can-book")
    public CanBook canBook(@RequestParam String tenantId, @RequestParam String officeId,
                           @RequestParam long departureInMinutes) {
        // one resolve gives the value and the version it came from together, and it is timed for the read metric
        Resolved resolved = client.explain(tenantId, "booking.cutoff", new ScopePath(tenantId, officeId, null));
        Duration cutoff = (Duration) resolved.value();
        boolean allowed = Duration.ofMinutes(departureInMinutes).compareTo(cutoff) >= 0;
        return new CanBook(allowed, cutoff.toString(), resolved.version());
    }

    public record CanBook(boolean allowed, String cutoff, long configVersion) {
    }
}
