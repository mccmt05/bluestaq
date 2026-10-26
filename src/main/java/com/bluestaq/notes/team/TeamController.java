package com.bluestaq.notes.team;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Exposes team discovery using the authenticated principal rather than a caller-supplied user ID.
 */
@RestController
public class TeamController {
    private final TeamService service;

    /**
     * Connects HTTP team requests to the application service.
     *
     * @param service service that lists the authenticated user's teams
     */
    public TeamController(TeamService service) {
        this.service = service;
    }

    /**
     * Handles {@code GET /api/teams} and lists only the current user's memberships.
     *
     * @param principal identity established by Spring Security
     * @return an HTTP 200 JSON list of accessible teams
     */
    @GetMapping("/api/teams")
    List<Team> list(Principal principal) {
        return service.list(principal.getName());
    }
}
