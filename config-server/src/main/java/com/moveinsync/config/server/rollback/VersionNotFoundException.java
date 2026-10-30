package com.moveinsync.config.server.rollback;

public class VersionNotFoundException extends RuntimeException {

    public VersionNotFoundException(long version) {
        super("version " + version + " not found");
    }
}
