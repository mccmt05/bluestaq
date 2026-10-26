package com.bluestaq.notes;

import com.bluestaq.notes.note.CreateNoteRequest;
import com.bluestaq.notes.note.Note;
import com.bluestaq.notes.note.NoteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the local launcher's data survives actual application and PostgreSQL restarts.
 * Each test uses a temporary directory rather than the developer's persistent {@code .local-db} folder.
 */
class LocalDevelopmentTest {
    @TempDir Path temporaryDirectory;

    /**
     * Writes a note in one application instance, then reads the same committed representation as a
     * teammate after closing and restarting both the application context and database process.
     */
    @Test
    void notesSurviveApplicationAndDatabaseRestarts() {
        Note saved;
        try (var application = startApplication()) {
            saved = application.getBean(NoteService.class).create("alice",
                    UUID.fromString("11111111-1111-4111-8111-111111111111"),
                    new CreateNoteRequest("Survives restart", "Stored in PostgreSQL"));
        }
        try (var restarted = startApplication()) {
            Note loaded = restarted.getBean(NoteService.class).get("bob", saved.teamId(), saved.id());
            assertThat(loaded).isEqualTo(saved);
        }
    }

    /**
     * Starts an isolated local application on an available HTTP port using this test's reusable data directory.
     *
     * @return a running context that the caller must close before restarting against the same directory
     */
    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(TeamNotesApplication.class, LocalDevelopment.LocalDatabase.class)
                .profiles("local")
                .properties("server.port=0", "notes.local-data-directory=" + temporaryDirectory.resolve("data"),
                        "spring.main.banner-mode=off", "logging.level.root=WARN")
                .run();
    }
}
