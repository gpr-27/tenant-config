package com.moveinsync.config.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.change.ChangeSet;
import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.Snapshot;
import com.moveinsync.config.core.snapshot.SnapshotCodec;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

// the B7.1 seed definitions, written in the same JSON the server seeds from
public final class Seeds {

    private static final String DEFINITIONS = """
            [
              {"key": "booking.cutoff", "type": "DURATION",
               "rules": {"min": "PT0M", "max": "PT24H", "lessThan": "booking.maxAdvance"},
               "default": "PT60M", "description": "How late a cab can be booked before departure"},
              {"key": "booking.maxAdvance", "type": "DURATION", "rules": {"min": "PT1H", "max": "PT720H"},
               "default": "PT168H", "description": "How far ahead a cab can be booked"},
              {"key": "ride.maxDuration", "type": "DURATION", "rules": {"min": "PT15M", "max": "PT6H"},
               "default": "PT2H", "description": "Longest allowed ride"},
              {"key": "night.window", "type": "TIME_WINDOW", "rules": {"allowOvernight": true},
               "default": {"start": "22:00", "end": "06:00", "overnight": true}, "description": "Night shift hours"},
              {"key": "office.hours", "type": "TIME_WINDOW", "rules": {},
               "default": {"start": "08:00", "end": "20:00"}, "description": "Office opening hours"},
              {"key": "escort.required", "type": "ENUM", "rules": {"values": ["true", "false"]},
               "default": "false", "description": "Whether night rides need an escort"},
              {"key": "cab.types", "type": "LIST",
               "rules": {"itemType": "ENUM", "values": ["SEDAN", "SUV", "TEMPO"], "maxItems": 3},
               "default": ["SEDAN", "SUV"], "description": "Cab types a tenant may book"},
              {"key": "booking.maxActive", "type": "NUMBER", "rules": {"integerOnly": true, "min": 1, "max": 10},
               "default": 3, "description": "Open bookings per employee"}
            ]
            """;

    private Seeds() {
    }

    public static List<SettingDefinition> definitions() {
        List<SettingDefinition> definitions = new ArrayList<>();
        for (JsonNode node : json(DEFINITIONS)) {
            definitions.add(SnapshotCodec.definitionFromJson(node));
        }
        return definitions;
    }

    public static SettingDefinition definition(String key) {
        for (SettingDefinition definition : definitions()) {
            if (definition.key().equals(key)) {
                return definition;
            }
        }
        throw new IllegalArgumentException(key);
    }

    // one version per definition, as the server's seeding does: the result is at version 8
    public static Snapshot snapshot() {
        Snapshot snapshot = Snapshot.empty();
        for (SettingDefinition definition : definitions()) {
            snapshot = snapshot.withDefinition(snapshot.version() + 1, definition);
        }
        return snapshot;
    }

    public static JsonNode json(String text) {
        try {
            return SnapshotCodec.mapper().readTree(text);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Operation set(ScopeKey scope, String key, String valueJson) {
        return new Operation(OpKind.SET, scope, key, json(valueJson));
    }

    public static Operation unset(ScopeKey scope, String key) {
        return new Operation(OpKind.UNSET, scope, key, null);
    }

    public static ChangeSet changeSet(String tenantId, Operation... operations) {
        return new ChangeSet(tenantId, "ravi", "test", List.of(operations));
    }
}
