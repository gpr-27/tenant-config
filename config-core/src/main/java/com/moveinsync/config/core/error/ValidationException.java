package com.moveinsync.config.core.error;

import java.util.List;

public class ValidationException extends ConfigException {

    private final List<ValidationError> errors;

    public ValidationException(List<ValidationError> errors) {
        super(errors.size() + " operation(s) failed validation");
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }

    @Override
    public String errorCode() {
        return "VALIDATION_FAILED";
    }
}
