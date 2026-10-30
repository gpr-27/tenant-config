package com.moveinsync.config.client.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.SnapshotCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public class FileSnapshotStore implements SnapshotStore {

    private static final System.Logger log = System.getLogger(FileSnapshotStore.class.getName());

    private final Path file;
    private final Path temp;

    public FileSnapshotStore(Path file) {
        this.file = file;
        this.temp = file.resolveSibling(file.getFileName() + ".tmp");
    }

    @Override
    public Optional<Snapshot> load() {
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(read());
        } catch (IOException | RuntimeException e) {
            // a corrupt copy is no worse than no copy: start empty and let the server fill us in
            log.log(Level.WARNING, "ignoring unreadable snapshot file " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private Snapshot read() throws IOException {
        JsonNode json = SnapshotCodec.mapper().readTree(file.toFile());
        // an empty or truncated file parses to nothing rather than failing, so check for the one field every snapshot has
        if (json == null || !json.has("version")) {
            throw new IOException("not a snapshot");
        }
        return SnapshotCodec.fromJson(json);
    }

    @Override
    public void save(Snapshot snapshot) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            SnapshotCodec.mapper().writeValue(temp.toFile(), SnapshotCodec.toJson(snapshot));
            moveIntoPlace(temp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save snapshot " + snapshot.version() + " to " + file, e);
        }
    }

    // a crash before this rename leaves the previous file untouched; overridden by the atomicity test to fail here
    protected void moveIntoPlace(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
