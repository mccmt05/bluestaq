package com.bluestaq.notes.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Replaces a note's editable text using the version the client last read.
 * Both text fields are required; this API does not treat missing fields as partial updates.
 * The controller validates the normalized values before the service attempts the versioned write.
 * Both text fields reject NUL characters, which PostgreSQL cannot store.
 *
 * @param title replacement title, stripped and required to contain 1-200 UTF-16 code units
 * @param content replacement plain text, required but allowed to be empty, limited to 20,000 UTF-16 code units
 * @param version positive version the client expects to replace, used to reject stale edits
 */
public record UpdateNoteRequest(
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL (U+0000) characters") String title,
        @NotNull @Size(max = 20000)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL (U+0000) characters") String content,
        @NotNull @Positive Long version) {
    /**
     * Normalizes the replacement title without changing the content or expected version.
     * Invalid or null values are left for controller-triggered Bean Validation to reject.
     *
     * @param title unnormalized replacement title
     * @param content replacement content, including intentional whitespace
     * @param version version from the client's most recent read
     */
    public UpdateNoteRequest {
        title = title == null ? null : title.strip();
    }
}
