package com.moveinsync.config.server;

import com.moveinsync.config.server.state.StateLoader;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseReset {

    private DatabaseReset() {
    }

    // empty tables, then the normal start-up path: definitions are seeded again as versions 1..8
    public static void reset(JdbcTemplate jdbc, StateLoader stateLoader) {
        jdbc.execute("truncate config_change, config_override, setting_definition");
        stateLoader.load();
    }
}
