package com.bluestaq.notes.team;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads teams and memberships with parameterized SQL against the authentication user table.
 * Membership lookups supply both an access decision and the user ID needed to record note authorship.
 */
@Repository
public class TeamRepository {
    private final JdbcClient jdbc;

    /**
     * Provides database access for team and membership queries.
     *
     * @param jdbc client connected to the application's PostgreSQL database
     */
    public TeamRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lists only teams containing the named user, ordered by name and then ID for stable results.
     *
     * @param username authenticated username supplied by Spring Security
     * @return the user's teams, or an empty list when there are no matching memberships
     */
    public List<Team> findTeams(String username) {
        return jdbc.sql("""
                SELECT t.id, t.name
                FROM team t
                JOIN membership m ON m.team_id = t.id
                JOIN app_user u ON u.id = m.user_id
                WHERE u.username = :username
                ORDER BY t.name, t.id
                """)
                .param("username", username)
                .query(Team.class).list();
    }

    /**
     * Resolves a user's ID only when a membership exists in the requested team.
     * Missing users, missing teams, and non-membership all produce an empty result.
     *
     * @param teamId team whose membership is being checked
     * @param username authenticated username supplied by Spring Security
     * @return the member's user ID, or empty when the user has no access to this team
     */
    public Optional<UUID> findMemberId(UUID teamId, String username) {
        return jdbc.sql("""
                SELECT m.user_id
                FROM membership m JOIN app_user u ON u.id = m.user_id
                WHERE m.team_id = :teamId AND u.username = :username
                """)
                .param("teamId", teamId)
                .param("username", username)
                .query(UUID.class).optional();
    }
}
