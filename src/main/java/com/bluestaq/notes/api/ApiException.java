package com.bluestaq.notes.api;

import org.springframework.http.HttpStatus;

/**
 * Signals an expected service failure with an HTTP status and a message safe to expose to clients.
 * {@link ApiExceptionHandler} converts it into the API's standard JSON error response.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    /**
     * Associates an expected application failure with its response status.
     *
     * @param status HTTP status for the failure
     * @param message client-safe description
     */
    private ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Supplies the response status used by the exception handler.
     *
     * @return the HTTP status associated with this failure
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Creates a not-found failure for a missing resource or an inaccessible team.
     * Using the same status for those cases avoids revealing another team's existence.
     *
     * @param resource public resource label, such as {@code Team} or {@code Note}
     * @return an exception mapped to HTTP 404
     */
    public static ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, resource + " not found");
    }

    /**
     * Reports an edit based on a stale version and tells the client to fetch the latest note.
     *
     * @return an exception mapped to HTTP 409
     */
    public static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT,
                "Note has changed. Fetch the latest version before updating.");
    }
}
