package com.bluestaq.notes.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Exposes an unauthenticated health endpoint for local checks and container health monitoring.
 * A healthy response requires a successful database query, not just a running HTTP listener.
 */
@RestController
public class HealthController {
    private final JdbcClient jdbc;

    /**
     * Connects health checks to the application's configured database.
     *
     * @param jdbc client used to verify PostgreSQL connectivity
     */
    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Checks database connectivity before reporting that the service is up.
     * Database failures propagate to the shared exception handler and produce a failing HTTP response.
     *
     * @return a status object containing {@code UP} after the database query succeeds
     */
    @GetMapping("/health")
    Map<String, String> health() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "UP");
    }
}
