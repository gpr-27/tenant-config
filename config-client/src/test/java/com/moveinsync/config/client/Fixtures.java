package com.moveinsync.config.client;

import com.fasterxml.jackson.databind.node.TextNode;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.change.OverridesChanged;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Snapshot;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// one DURATION setting and events that move its BLR office override, enough to see order and content
public final class Fixtures {

    public static final ScopePath BLR = new ScopePath("acme", "blr-ecospace", null);

    private static final SettingDefinition CUTOFF = new SettingDefinition("booking.cutoff",
            SettingTypes.of("DURATION"), new Rules(Map.of()), Duration.ofMinutes(60), "");

    private Fixtures() {
    }

    public static ConfigClientOptions options() {
        return ConfigClientOptions.defaults(URI.create("http://config.invalid"), () -> "token", Path.of("unused.json"));
    }

    // a snapshot at the given version with no overrides: booking.cutoff is the 60 min default
    public static Snapshot snapshotAt(long version) {
        return Snapshot.of(version, Map.of(CUTOFF.key(), CUTOFF), Map.of());
    }

    public static ChangeEvent cutoff(long version, String duration) {
        Operation set = new Operation(OpKind.SET, ScopeKey.office("blr-ecospace"), "booking.cutoff",
                TextNode.valueOf(duration));
        return new ChangeEvent(version, ChangeKind.CHANGE,
                new OverridesChanged(new ChangeSet("acme", "ravi", "test", List.of(set))), Instant.now());
    }

    public static Duration cutoffIn(ConfigClient client) {
        return client.get("acme", "booking.cutoff", BLR, Duration.class);
    }
}
