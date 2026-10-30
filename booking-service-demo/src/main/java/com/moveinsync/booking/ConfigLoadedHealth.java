package com.moveinsync.booking;

import com.moveinsync.config.client.ConfigClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

// down until the first snapshot (from the server or the disk copy): before that every booking answers 503
@Component("config")
public class ConfigLoadedHealth implements HealthIndicator {

    private final ConfigClient client;

    public ConfigLoadedHealth(ConfigClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        long version = client.version();
        Health.Builder health = version > 0 ? Health.up() : Health.down();
        return health.withDetail("version", version).build();
    }
}
