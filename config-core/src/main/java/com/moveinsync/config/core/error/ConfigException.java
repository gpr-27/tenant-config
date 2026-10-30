package com.moveinsync.config.core.error;

public abstract class ConfigException extends RuntimeException {

    protected ConfigException(String message) {
        super(message);
    }

    public abstract String errorCode();
}
