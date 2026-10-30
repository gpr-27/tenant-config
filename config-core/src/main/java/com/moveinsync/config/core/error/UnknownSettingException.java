package com.moveinsync.config.core.error;

public class UnknownSettingException extends ConfigException {

    private final String key;

    public UnknownSettingException(String key) {
        super("unknown setting: " + key);
        this.key = key;
    }

    public String key() {
        return key;
    }

    @Override
    public String errorCode() {
        return "UNKNOWN_SETTING";
    }
}
