package com.moveinsync.config.server.web;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.ArrayList;
import java.util.List;

// one entry of a 400's errors[]: the field and the constraint it broke, never the value that broke it
public record FieldProblem(String field, String code, String message) {

    static List<FieldProblem> from(List<ObjectError> errors) {
        List<FieldProblem> problems = new ArrayList<>();
        for (ObjectError error : errors) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            problems.add(new FieldProblem(field, constraint(error), error.getDefaultMessage()));
        }
        return problems;
    }

    // a constraint on a plain parameter (?limit=0) comes from method validation instead of data binding
    static List<FieldProblem> from(HandlerMethodValidationException e) {
        List<FieldProblem> problems = new ArrayList<>();
        for (ParameterValidationResult result : e.getAllValidationResults()) {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError ? fieldError.getField() : parameter;
                problems.add(new FieldProblem(field, constraint(error), error.getDefaultMessage()));
            }
        }
        return problems;
    }

    // Spring lists the codes from most to least specific; the last one is the bare constraint name ("NotBlank")
    private static String constraint(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? null : codes[codes.length - 1];
    }
}
