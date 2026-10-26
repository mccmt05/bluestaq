package com.bluestaq.notes.note;

import com.bluestaq.notes.api.ApiException;
import com.bluestaq.notes.team.TeamService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Enforces team membership and coordinates transactions for every note operation.
 * All members of a team have equal note permissions, regardless of the original author.
 * Read operations are read-only by default; writes override that transaction setting explicitly.
 */
@Service
@Transactional(readOnly = true)
public class NoteService {
    private final NoteRepository notes;
    private final TeamService teams;

    /**
     * Combines note persistence with the shared team authorization policy.
     *
     * @param notes repository responsible for team-scoped note SQL
     * @param teams service that validates membership and supplies the current user's ID
     */
    public NoteService(NoteRepository notes, TeamService teams) {
        this.notes = notes;
        this.teams = teams;
    }

    /**
     * Lists an authorized team's notes and total count from one repeatable-read snapshot.
     * Pagination bounds are validated by the HTTP controller before this method is called.
     *
     * @param username authenticated caller
     * @param teamId team whose notes are requested
     * @param page nonnegative, zero-based page number
     * @param size validated page size between 1 and 100
     * @return the requested page and consistent pagination metadata
     * @throws ApiException with HTTP 404 if the caller is not a member of the team
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotePage list(String username, UUID teamId, int page, int size) {
        teams.requireMember(username, teamId);
        return new NotePage(notes.findAll(teamId, page, size), page, size, notes.count(teamId));
    }

    /**
     * Reads a note after checking the caller's membership in the team named by the request path.
     *
     * @param username authenticated caller
     * @param teamId expected owning team
     * @param noteId requested note
     * @return the stored note and its current version
     * @throws ApiException with HTTP 404 if the team is inaccessible or the note is absent from it
     */
    public Note get(String username, UUID teamId, UUID noteId) {
        teams.requireMember(username, teamId);
        return findNote(teamId, noteId);
    }

    /**
     * Creates a shared note and records the authorized caller as its original author and current editor.
     *
     * @param username authenticated caller
     * @param teamId team that will own the note
     * @param request normalized and validated note text
     * @return the persisted note with server-generated metadata and version 1
     * @throws ApiException with HTTP 404 if the caller cannot access the team
     */
    @Transactional
    public Note create(String username, UUID teamId, CreateNoteRequest request) {
        UUID userId = teams.requireMember(username, teamId);
        return notes.create(teamId, userId, request);
    }

    /**
     * Applies a full text replacement using an atomic expected-version check.
     * When no row is updated, a scoped lookup distinguishes a missing note from a stale edit.
     *
     * @param username authenticated caller
     * @param teamId expected owning team
     * @param noteId note to replace
     * @param request validated replacement text and the client's expected version
     * @return the updated note with an incremented version
     * @throws ApiException with HTTP 404 for an inaccessible team or missing note, or 409 for a stale version
     */
    @Transactional
    public Note update(String username, UUID teamId, UUID noteId, UpdateNoteRequest request) {
        UUID userId = teams.requireMember(username, teamId);
        return notes.update(teamId, noteId, userId, request).orElseGet(() -> {
            findNote(teamId, noteId);
            throw ApiException.conflict();
        });
    }

    /**
     * Permanently deletes an authorized team's note without requiring an expected version.
     * Repeating a successful deletion produces not found rather than another successful response.
     *
     * @param username authenticated caller
     * @param teamId expected owning team
     * @param noteId note to remove
     * @throws ApiException with HTTP 404 for an inaccessible team or a note absent from that team
     */
    @Transactional
    public void delete(String username, UUID teamId, UUID noteId) {
        teams.requireMember(username, teamId);
        if (!notes.delete(teamId, noteId)) {
            throw ApiException.notFound("Note");
        }
    }

    /**
     * Converts an empty team-scoped lookup into the API's common note-not-found failure.
     * Callers must perform the membership check before using this helper.
     *
     * @param teamId expected owning team
     * @param noteId requested note
     * @return the matching stored note
     * @throws ApiException with HTTP 404 when the note does not exist in the requested team
     */
    private Note findNote(UUID teamId, UUID noteId) {
        return notes.find(teamId, noteId).orElseThrow(() -> ApiException.notFound("Note"));
    }
}
