package com.bluestaq.notes.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts controller and service failures into consistent, client-safe JSON responses.
 * Unexpected failures are logged on the server while clients receive a generic message.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Preserves the status and public message of an expected application failure.
     *
     * @param exception failure raised by an application service
     * @return the standard error body with the exception's HTTP status
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        HttpStatus status = exception.status();
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.name(), exception.getMessage()));
    }

    /**
     * Collects one Bean Validation message per invalid request field.
     *
     * @param exception validation failure containing the binding results
     * @param headers response headers supplied by Spring
     * @param status validation status supplied by Spring
     * @param request request associated with the failure
     * @return an HTTP 400 response with field-specific validation details
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException exception,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "BAD_REQUEST", "Request validation failed", fields));
    }

    /**
     * Replaces Spring MVC's default error body with the API format for framework-handled failures,
     * including malformed JSON, invalid parameters, and unsupported request methods.
     *
     * @param exception framework-handled failure
     * @param body proposed framework error body, intentionally replaced and possibly {@code null}
     * @param headers framework response headers to preserve
     * @param status HTTP status selected by Spring MVC
     * @param request request associated with the failure
     * @return a consistent error response without exposing internal exception messages
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception exception, @Nullable Object body,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved == null ? "HTTP_ERROR" : resolved.name();
        String message = status.value() == 400 ? "Invalid request fields, parameters, or JSON"
                : resolved == null ? "Request failed" : resolved.getReasonPhrase();
        return new ResponseEntity<>(new ApiError(status.value(), code, message), headers, status);
    }

    /**
     * Logs an unexpected failure and returns a generic error instead of its internal details.
     *
     * @param exception unhandled controller or service failure
     * @return an HTTP 500 response with a client-safe message
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOG.error("Unexpected request failure", exception);
        return ResponseEntity.internalServerError()
                .body(new ApiError(500, "INTERNAL_SERVER_ERROR", "An unexpected error occurred"));
    }
}
