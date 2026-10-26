package com.bluestaq.notes.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Provides the shared JSON error format for authentication, request validation, and application failures.
 * Empty field-error details are omitted from the serialized response.
 *
 * @param status HTTP status code returned to the client
 * @param code stable error category, normally the corresponding HTTP status name
 * @param message client-safe explanation without internal exception or database details
 * @param fieldErrors validation messages keyed by request field, or an empty map for general errors
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(int status, String code, String message, Map<String, String> fieldErrors) {
    /**
     * Creates a general error response with no field-specific validation messages.
     *
     * @param status HTTP status code returned to the client
     * @param code stable error category
     * @param message client-safe explanation of the failure
     */
    public ApiError(int status, String code, String message) {
        this(status, code, message, Map.of());
    }
}
