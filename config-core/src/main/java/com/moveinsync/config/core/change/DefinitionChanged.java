package com.moveinsync.config.core.change;

import com.moveinsync.config.core.definition.SettingDefinition;

// create or update
public record DefinitionChanged(SettingDefinition definition) implements ChangePayload {
}
