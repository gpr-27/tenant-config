package com.moveinsync.config.core.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.moveinsync.config.core.error.InvalidValueException;

import java.math.BigDecimal;

public final class NumberType implements SettingType {

    NumberType() {
    }

    @Override
    public String name() {
        return "NUMBER";
    }

    @Override
    public Object parse(JsonNode raw) {
        if (raw == null || !raw.isNumber()) {
            throw mismatch();
        }
        // one form per number (10, 10.0 and 1E+1 all become 10), so equal values compare equal everywhere
        BigDecimal number = raw.decimalValue().stripTrailingZeros();
        return number.scale() < 0 ? number.setScale(0) : number;
    }

    @Override
    public void validate(Object value, Rules rules) {
        if (!(value instanceof BigDecimal number)) {
            throw mismatch();
        }
        // "fractional when integerOnly" has no code of its own in the spec; it is the wrong kind of number
        if (rules.integerOnly() && number.stripTrailingZeros().scale() > 0) {
            throw new InvalidValueException("TYPE_MISMATCH", "must be a whole number");
        }
        if (rules.min() != null && number.compareTo(rules.min().decimalValue()) < 0) {
            throw new InvalidValueException("OUT_OF_RANGE", "must be at least " + rules.min().asText());
        }
        if (rules.max() != null && number.compareTo(rules.max().decimalValue()) > 0) {
            throw new InvalidValueException("OUT_OF_RANGE", "must be at most " + rules.max().asText());
        }
    }

    @Override
    public JsonNode toJson(Object value) {
        // DecimalNode directly: the node factory would strip zeros and write 10 as 1E+1
        return DecimalNode.valueOf((BigDecimal) value);
    }

    private static InvalidValueException mismatch() {
        return new InvalidValueException("TYPE_MISMATCH", "expected a number");
    }
}
