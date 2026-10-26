package com.bluestaq.notes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the production application and enables Spring's component scanning and auto-configuration.
 * Database access uses external PostgreSQL configuration; the separate test-scoped launcher supplies
 * an embedded database for local development.
 */
@SpringBootApplication
public class TeamNotesApplication {
    /**
     * Launches the HTTP service, applying Flyway migrations before it accepts requests.
     *
     * @param args Spring Boot command-line options, such as {@code --server.port=8081}
     */
    public static void main(String[] args) {
        SpringApplication.run(TeamNotesApplication.class, args);
    }
}
