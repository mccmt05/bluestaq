package com.bluestaq.notes.note;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

/**
 * Maps team-scoped note REST endpoints to the authorization-aware application service.
 * Spring Security supplies identity, and Bean Validation checks request bodies and pagination bounds.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/notes")
public class NoteController {
    private final NoteService service;

    /**
     * Connects note HTTP requests to their authorization and transaction boundary.
     *
     * @param service service implementing shared note operations
     */
    public NoteController(NoteService service) {
        this.service = service;
    }

    /**
     * Handles a paginated {@code GET} on a team's note collection.
     *
     * @param principal authenticated caller
     * @param teamId owning team from the request path
     * @param page zero-based page number, defaulting to 0
     * @param size maximum page size, defaulting to 20 and limited to 1-100
     * @return an HTTP 200 page of notes and the team's total count
     */
    @GetMapping
    NotePage list(Principal principal, @PathVariable UUID teamId,
                  @RequestParam(defaultValue = "0") @Min(0) int page,
                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(principal.getName(), teamId, page, size);
    }

    /**
     * Handles creation of a team note from its editable text fields.
     *
     * @param principal authenticated author
     * @param teamId team that will own the note
     * @param request normalized text validated before the service is invoked
     * @return HTTP 201 with the stored note and a {@code Location} header for its read endpoint
     */
    @PostMapping
    ResponseEntity<Note> create(Principal principal, @PathVariable UUID teamId,
                                @Valid @RequestBody CreateNoteRequest request) {
        Note note = service.create(principal.getName(), teamId, request);
        URI location = URI.create("/api/teams/" + teamId + "/notes/" + note.id());
        return ResponseEntity.created(location).body(note);
    }

    /**
     * Handles reading a single note within the team named by the request path.
     *
     * @param principal authenticated caller
     * @param teamId expected owning team
     * @param noteId requested note identifier
     * @return an HTTP 200 note representation when membership and ownership checks succeed
     */
    @GetMapping("/{noteId}")
    Note get(Principal principal, @PathVariable UUID teamId, @PathVariable UUID noteId) {
        return service.get(principal.getName(), teamId, noteId);
    }

    /**
     * Handles full text replacement with optimistic concurrency through the submitted note version.
     * The service reports stale versions as HTTP 409 so clients can fetch and reconcile newer content.
     *
     * @param principal authenticated editor
     * @param teamId expected owning team
     * @param noteId note to replace
     * @param request validated title, content, and expected version
     * @return an HTTP 200 representation containing the new version
     */
    @PutMapping("/{noteId}")
    Note update(Principal principal, @PathVariable UUID teamId, @PathVariable UUID noteId,
                @Valid @RequestBody UpdateNoteRequest request) {
        return service.update(principal.getName(), teamId, noteId, request);
    }

    /**
     * Handles permanent deletion by any member of the note's team, without a version precondition.
     *
     * @param principal authenticated caller
     * @param teamId expected owning team
     * @param noteId note to remove
     * @return HTTP 204 with no response body after a successful deletion
     */
    @DeleteMapping("/{noteId}")
    ResponseEntity<Void> delete(Principal principal, @PathVariable UUID teamId, @PathVariable UUID noteId) {
        service.delete(principal.getName(), teamId, noteId);
        return ResponseEntity.noContent().build();
    }
}
