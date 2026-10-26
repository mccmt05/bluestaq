package com.bluestaq.notes.note;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a persisted team-owned note and the complete JSON representation returned by the API.
 * Creation metadata stays fixed; successful edits replace the text, update edit metadata, and increment the version.
 *
 * @param id server-generated note identifier
 * @param teamId owning team, which cannot change through this API
 * @param title normalized note title
 * @param content plain-text note content, possibly empty
 * @param createdBy user ID of the original author
 * @param updatedBy user ID of the most recent editor
 * @param createdAt database-generated creation instant, serialized in UTC
 * @param updatedAt database-generated instant of the most recent write, serialized in UTC
 * @param version optimistic concurrency version, starting at 1
 */
public record Note(UUID id, UUID teamId, String title, String content, UUID createdBy,
                   UUID updatedBy, Instant createdAt, Instant updatedAt, long version) {
}
