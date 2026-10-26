package com.bluestaq.notes.note;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists notes with parameterized PostgreSQL statements scoped to both team and note identity.
 * {@link NoteService} is responsible for validating membership and choosing transaction boundaries.
 * Updates check the expected version atomically in SQL to prevent silent lost edits.
 */
@Repository
public class NoteRepository {
    private static final RowMapper<Note> NOTE_MAPPER = (row, index) -> new Note(
            row.getObject("id", UUID.class),
            row.getObject("team_id", UUID.class),
            row.getString("title"),
            row.getString("content"),
            row.getObject("created_by", UUID.class),
            row.getObject("updated_by", UUID.class),
            row.getObject("created_at", OffsetDateTime.class).toInstant(),
            row.getObject("updated_at", OffsetDateTime.class).toInstant(),
            row.getLong("version"));

    private final JdbcClient jdbc;

    /**
     * Supplies the database client used by every note query and write.
     *
     * @param jdbc client connected to the application's PostgreSQL database
     */
    public NoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reads a bounded page in descending update-time and ID order.
     * Membership and pagination validation must be completed by the caller.
     *
     * @param teamId team whose notes are being listed
     * @param page nonnegative, zero-based page number
     * @param size positive maximum number of notes to return
     * @return matching notes, or an empty list when the page is beyond the team's results
     */
    public List<Note> findAll(UUID teamId, int page, int size) {
        return jdbc.sql("""
                SELECT * FROM note WHERE team_id = :teamId
                ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset
                """)
                .param("teamId", teamId)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(NOTE_MAPPER).list();
    }

    /**
     * Counts all notes in one team for pagination metadata.
     * The service shares a repeatable-read transaction with the page query for a consistent total.
     *
     * @param teamId team whose notes are counted
     * @return the team's total number of notes
     */
    public long count(UUID teamId) {
        return jdbc.sql("SELECT count(*) FROM note WHERE team_id = :teamId")
                .param("teamId", teamId).query(Long.class).single();
    }

    /**
     * Reads a note only within the requested team, preventing note-ID substitution across team paths.
     *
     * @param teamId expected owning team
     * @param noteId requested note identifier
     * @return the note, or empty when it does not exist in that team
     */
    public Optional<Note> find(UUID teamId, UUID noteId) {
        return jdbc.sql("SELECT * FROM note WHERE team_id = :teamId AND id = :noteId")
                .param("teamId", teamId)
                .param("noteId", noteId)
                .query(NOTE_MAPPER).optional();
    }

    /**
     * Inserts a note with a new UUID, database timestamps, version 1, and the caller as both author and editor.
     * The returned representation comes directly from the inserted row.
     *
     * @param teamId owning team, already authorized by the service
     * @param userId authenticated team member creating the note
     * @param request normalized and validated note text
     * @return the stored note including all server-controlled metadata
     */
    public Note create(UUID teamId, UUID userId, CreateNoteRequest request) {
        return jdbc.sql("""
                INSERT INTO note (id, team_id, title, content, created_by, updated_by)
                VALUES (:id, :teamId, :title, :content, :userId, :userId)
                RETURNING *
                """)
                .param("id", UUID.randomUUID())
                .param("teamId", teamId)
                .param("title", request.title())
                .param("content", request.content())
                .param("userId", userId)
                .query(NOTE_MAPPER).single();
    }

    /**
     * Replaces text only when the team, note ID, and expected version all match the stored row.
     * PostgreSQL performs the comparison and write under the row lock; a successful write increments
     * the version and returns that write's exact representation without a separate read.
     *
     * @param teamId expected owning team, already authorized by the service
     * @param noteId note to replace
     * @param userId authenticated member responsible for the edit
     * @param request replacement text and the version the client expects
     * @return the updated note, or empty for a missing note, wrong team, or stale version
     */
    public Optional<Note> update(UUID teamId, UUID noteId, UUID userId, UpdateNoteRequest request) {
        // The database evaluates the expected version while holding the row lock.
        // RETURNING supplies exactly the representation this write produced.
        return jdbc.sql("""
                UPDATE note
                SET title = :title, content = :content, updated_by = :userId,
                    updated_at = clock_timestamp(), version = version + 1
                WHERE team_id = :teamId AND id = :noteId AND version = :version
                RETURNING *
                """)
                .param("teamId", teamId)
                .param("noteId", noteId)
                .param("userId", userId)
                .param("title", request.title())
                .param("content", request.content())
                .param("version", request.version())
                .query(NOTE_MAPPER).optional();
    }

    /**
     * Permanently removes a note from its expected team without a version precondition.
     *
     * @param teamId expected owning team, already authorized by the service
     * @param noteId note to remove
     * @return {@code true} if one note was deleted, or {@code false} when no row matched
     */
    public boolean delete(UUID teamId, UUID noteId) {
        return jdbc.sql("DELETE FROM note WHERE team_id = :teamId AND id = :noteId")
                .param("teamId", teamId)
                .param("noteId", noteId).update() == 1;
    }
}
