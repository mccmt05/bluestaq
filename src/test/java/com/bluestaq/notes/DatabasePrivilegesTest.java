package com.bluestaq.notes;

import com.bluestaq.notes.note.CreateNoteRequest;
import com.bluestaq.notes.note.NoteService;
import com.bluestaq.notes.note.UpdateNoteRequest;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the Compose role SQL and Flyway grant callback against temporary PostgreSQL without Docker.
 * Separate owner and runtime connections verify that startup and note operations need no runtime DDL privileges.
 */
class DatabasePrivilegesTest {
    /**
     * Migrates as the owner, runs repositories as the restricted role, and rejects identity changes and DDL.
     * The initialization SQL is the same file mounted by Compose; only psql's password substitution is performed here.
     *
     * @throws Exception if the temporary database, initialization script, or application cannot start
     */
    @Test
    void runtimeRoleSupportsTheApiWithoutAdministrativePrivileges() throws Exception {
        try (var database = EmbeddedPostgres.builder().start()) {
            var bootstrap = new JdbcTemplate(database.getPostgresDatabase());
            bootstrap.execute("CREATE DATABASE team_notes");
            var owner = new JdbcTemplate(database.getDatabase("postgres", "team_notes"));
            String roleSql = Files.readString(Path.of("docker/postgres/01-runtime-role.sql"))
                    .replace("\\getenv runtime_password DB_APP_PASSWORD", "")
                    .replace(":'runtime_password'", "'privileges-test-password'");
            owner.execute(roleSql);
            try (var application = SpringApplication.run(TeamNotesApplication.class,
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.profiles.active=local",
                    "--spring.datasource.url=" + database.getJdbcUrl("notes_app", "team_notes"),
                    "--spring.datasource.username=notes_app", "--spring.datasource.password=privileges-test-password",
                    "--spring.flyway.url=" + database.getJdbcUrl("postgres", "team_notes"),
                    "--spring.flyway.user=postgres", "--spring.flyway.password=postgres",
                    "--spring.flyway.locations=classpath:db/migration,classpath:db/local,classpath:db/permissions",
                    "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
                var runtime = application.getBean(JdbcTemplate.class);
                assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo("notes_app");
                // PostgreSQL's system catalog is outside the project's DDL-only IDE model.
                //noinspection SqlResolve
                assertThat(runtime.queryForObject("""
                        SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                        FROM pg_roles WHERE rolname = current_user
                        """, Boolean.class)).isFalse();
                assertThat(application.getBean(UserDetailsService.class).loadUserByUsername("alice").getUsername())
                        .isEqualTo("alice");
                var service = application.getBean(NoteService.class);
                var created = service.create("alice", UUID.fromString("11111111-1111-4111-8111-111111111111"),
                        new CreateNoteRequest("Restricted role", "Created"));
                var updated = service.update("bob", created.teamId(), created.id(),
                        new UpdateNoteRequest("Restricted role", "Updated", created.version()));
                assertThat(service.get("alice", created.teamId(), created.id())).isEqualTo(updated);
                service.delete("alice", created.teamId(), created.id());
                assertThat(runtime.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
                assertDenied(runtime, "CREATE TABLE forbidden_runtime_table (id INTEGER)");
                assertDenied(runtime, "UPDATE app_user SET password_hash = 'forbidden' WHERE username = 'alice'");
                assertDenied(runtime, "DELETE FROM membership WHERE user_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1'");
                assertDenied(runtime, "SELECT * FROM flyway_schema_history");
            }
        }
    }

    /**
     * Confirms PostgreSQL rejects a forbidden runtime statement with insufficient-privilege SQLSTATE.
     *
     * @param runtime restricted application's database connection
     * @param sql statement that the runtime role must not be allowed to execute
     */
    private void assertDenied(JdbcTemplate runtime, String sql) {
        assertThatThrownBy(() -> runtime.execute(sql)).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(exception -> {
                    Throwable cause = exception;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertThat(((SQLException) cause).getSQLState()).isEqualTo("42501");
                });
    }
}
