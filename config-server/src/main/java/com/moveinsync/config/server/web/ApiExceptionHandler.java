package com.moveinsync.config.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.error.InvalidValueException;
import com.moveinsync.config.core.error.UnknownSettingException;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.server.change.IdempotencyConflictException;
import com.moveinsync.config.server.push.TooManyConnectionsException;
import com.moveinsync.config.server.rollback.VersionNotFoundException;
import com.moveinsync.config.server.security.TenantAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;

// the one place exceptions become HTTP: RFC 7807 bodies plus errorCode, requestId and (for 422) errors[]
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> validation(ValidationException e, HttpServletRequest request) {
        return withErrors(e.getMessage(), e.errors(), request);
    }

    // a definition whose default or type is bad: same 422 shape as a rejected change set
    @ExceptionHandler(InvalidValueException.class)
    ResponseEntity<ProblemDetail> invalidValue(InvalidValueException e, HttpServletRequest request) {
        return withErrors("1 operation(s) failed validation",
                List.of(new ValidationError(0, null, e.errorCode(), e.getMessage())), request);
    }

    @ExceptionHandler(UnknownSettingException.class)
    ResponseEntity<ProblemDetail> unknownSetting(UnknownSettingException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "UNKNOWN_SETTING", e.getMessage(), request);
    }

    @ExceptionHandler(VersionNotFoundException.class)
    ResponseEntity<ProblemDetail> versionNotFound(VersionNotFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", e.getMessage(), request);
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ProblemDetail> otherTenant(TenantAccessDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", null, request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(IdempotencyConflictException e, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", e.getMessage(), request);
    }

    @ExceptionHandler(TooManyConnectionsException.class)
    ResponseEntity<ProblemDetail> pushCapacity(TooManyConnectionsException e, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_CAPACITY", e.getMessage(), request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    // TransactionException too: "cannot open a transaction" is how a dead database usually shows up
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> storage(RuntimeException e, HttpServletRequest request) {
        log.warn("storage unavailable: {}", e.getClass().getSimpleName());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "storage is unavailable", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden(AccessDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "your role may not do this", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthorized(AuthenticationException e, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), request);
    }

    // an SSE client that hung up mid-write; PushHub already dropped it and nothing can be sent back
    @ExceptionHandler(IOException.class)
    void clientGone(IOException e) {
        log.debug("response write failed: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Unexpected error", request);
    }

    // Spring's own 4xx (unreadable body, missing parameter, wrong method...) get the same two extra fields
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof NativeWebRequest web) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty("errorCode", status == null ? "HTTP_" + statusCode.value() : status.name());
            problem.setProperty("requestId",
                    RequestIdFilter.requestId(web.getNativeRequest(HttpServletRequest.class)));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // for answers written by the security filters, which run before any controller advice
    public void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                             String errorCode, String detail) throws IOException {
        ProblemDetail problem = problem(status, errorCode, detail, request);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private ResponseEntity<ProblemDetail> withErrors(String detail, List<ValidationError> errors,
                                                     HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", detail, request);
        problem.setProperty("errors", errors);
        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    // content type fixed, so an SSE request (Accept: text/event-stream) still gets a readable error
    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String errorCode, String detail,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(status, errorCode, detail, request));
    }

    private static ProblemDetail problem(HttpStatus status, String errorCode, String detail,
                                         HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("requestId", RequestIdFilter.requestId(request));
        return problem;
    }
}
