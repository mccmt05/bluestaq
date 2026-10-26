package com.bluestaq.notes.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Contains the client-editable fields for creating a team note.
 * The constructor normalizes the title; the controller triggers Bean Validation afterward.
 * IDs, team ownership, authorship, timestamps, and the initial version are supplied by the server.
 * Both text fields reject NUL characters, which PostgreSQL cannot store.
 *
 * @param title title stripped of surrounding whitespace, required to contain 1-200 UTF-16 code units
 * @param content plain-text content, required but allowed to be empty, with at most 20,000 UTF-16 code units
 */
public record CreateNoteRequest(
        @NotBlank @Size(max = 200)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL (U+0000) characters") String title,
        @NotNull @Size(max = 20000)
        @Pattern(regexp = "[^\\x00]*", message = "must not contain NUL (U+0000) characters") String content) {
    /**
     * Strips surrounding title whitespace before validation while preserving the content exactly.
     * Null values remain available for Bean Validation to report as invalid request fields.
     *
     * @param title unnormalized client title
     * @param content client content, including intentional whitespace
     */
    public CreateNoteRequest {
        title = title == null ? null : title.strip();
    }
}
