package com.moveinsync.config.core.error;

public class InvalidValueException extends ConfigException {

    private final String code;

    public InvalidValueException(String code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String errorCode() {
        return code;
    }
}
