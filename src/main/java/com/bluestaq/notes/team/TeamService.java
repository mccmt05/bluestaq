package com.bluestaq.notes.team;

import com.bluestaq.notes.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Provides team discovery and the common membership check used before accessing team-owned notes.
 * Read operations run in a read-only transaction unless they join a caller's existing transaction.
 */
@Service
@Transactional(readOnly = true)
public class TeamService {
    private final TeamRepository teams;

    /**
     * Supplies the membership persistence dependency.
     *
     * @param teams repository used for team discovery and membership lookups
     */
    public TeamService(TeamRepository teams) {
        this.teams = teams;
    }

    /**
     * Returns the authenticated user's teams in the repository's stable display order.
     *
     * @param username authenticated username
     * @return accessible teams, possibly empty
     */
    public List<Team> list(String username) {
        return teams.findTeams(username);
    }

    /**
     * Requires membership before a caller may access a team's resources.
     * A missing team and a team the caller cannot access deliberately have the same public error.
     *
     * @param username authenticated username
     * @param teamId team containing the requested resources
     * @return the authorized member's user ID for note authorship tracking
     * @throws ApiException with HTTP 404 when no matching membership exists
     */
    public UUID requireMember(String username, UUID teamId) {
        return teams.findMemberId(teamId, username)
                .orElseThrow(() -> ApiException.notFound("Team"));
    }
}
