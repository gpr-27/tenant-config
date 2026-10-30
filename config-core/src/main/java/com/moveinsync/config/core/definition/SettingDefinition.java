package com.moveinsync.config.core.definition;

public record SettingDefinition(String key, SettingType type, Rules rules, Object defaultValue, String description) {

    public SettingDefinition {
        type.validate(defaultValue, rules);
    }
}
