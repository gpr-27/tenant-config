package com.moveinsync.config.core.definition;

import com.moveinsync.config.core.error.InvalidValueException;

public final class SettingTypes {

    // the types hold no state, so one instance each is enough and definitions compare by identity
    public static final SettingType NUMBER = new NumberType();
    public static final SettingType DURATION = new DurationType();
    public static final SettingType TIME_WINDOW = new TimeWindowType();
    public static final SettingType ENUM = new EnumType();
    public static final SettingType LIST = new ListType();

    private SettingTypes() {
    }

    public static SettingType of(String name) {
        return switch (name) {
            case "NUMBER" -> NUMBER;
            case "DURATION" -> DURATION;
            case "TIME_WINDOW" -> TIME_WINDOW;
            case "ENUM" -> ENUM;
            case "LIST" -> LIST;
            default -> throw new InvalidValueException("UNKNOWN_TYPE",
                    "type must be one of NUMBER, DURATION, TIME_WINDOW, ENUM, LIST");
        };
    }
}
