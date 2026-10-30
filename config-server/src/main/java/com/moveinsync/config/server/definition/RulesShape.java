package com.moveinsync.config.server.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.moveinsync.config.core.error.ValidationError;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Core reads rules as it finds them: "abc" as a NUMBER min becomes 0 and a DURATION min of 5 throws a parse
// error mid-request. So the shape of every known rule is checked here, against the type, before core sees it.
// Messages never quote the submitted value.
final class RulesShape {

    private RulesShape() {
    }

    static List<ValidationError> check(String key, String type, JsonNode rules, Set<String> definedKeys) {
        List<ValidationError> errors = new ArrayList<>();
        if (!rules.isObject()) {
            errors.add(invalid(key, "rules must be a JSON object"));
            return errors;
        }
        for (Map.Entry<String, JsonNode> rule : rules.properties()) {
            String problem = problem(rule.getKey(), rule.getValue(), type, definedKeys);
            if (problem != null) {
                errors.add(invalid(key, "rules." + rule.getKey() + " " + problem));
            }
        }
        return errors;
    }

    // null when the rule is fine; a rule name core does not know is left alone, core never reads it
    private static String problem(String name, JsonNode value, String type, Set<String> definedKeys) {
        return switch (name) {
            case "min", "max" -> boundProblem(value, type);
            case "integerOnly", "allowOvernight" -> value.isBoolean() ? null : "must be true or false";
            case "values" -> isArrayOfStrings(value) ? null : "must be an array of strings";
            case "itemType" -> value.isTextual() && (value.asText().equals("ENUM") || value.asText().equals("STRING"))
                    ? null : "must be ENUM or STRING";
            case "maxItems" -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0
                    ? null : "must be a positive whole number";
            case "lessThan" -> value.isTextual() && definedKeys.contains(value.asText())
                    ? null : "must name an existing setting";
            default -> null;
        };
    }

    // only NUMBER and DURATION read min and max
    private static String boundProblem(JsonNode value, String type) {
        if (type.equals("NUMBER") && !value.isNumber()) {
            return "must be a number";
        }
        if (type.equals("DURATION") && !isDuration(value)) {
            return "must be an ISO-8601 duration such as PT30M";
        }
        return null;
    }

    private static boolean isDuration(JsonNode value) {
        if (!value.isTextual()) {
            return false;
        }
        try {
            Duration.parse(value.asText());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isArrayOfStrings(JsonNode value) {
        if (!value.isArray()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                return false;
            }
        }
        return true;
    }

    private static ValidationError invalid(String key, String message) {
        return new ValidationError(0, key, "INVALID_RULES", message);
    }
}
