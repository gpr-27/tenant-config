package com.moveinsync.booking;

import com.moveinsync.config.client.SnapshotListener;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigUpdateLogger implements SnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigUpdateLogger.class);

    @Override
    public void onSnapshot(Snapshot snapshot) {
        log.info("config updated version={}", snapshot.version());
    }
}
