package com.bluestaq.notes;

import com.bluestaq.notes.note.CreateNoteRequest;
import com.bluestaq.notes.note.Note;
import com.bluestaq.notes.note.NoteService;
import com.bluestaq.notes.note.UpdateNoteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises authentication, authorization, validation, and persistence against a real PostgreSQL process.
 * Local migrations supply fixed team memberships, and the note table is cleared before each test.
 * Requests use the real security filters and services, with live HTTP tests for body transfer framing.
 * Only the limiter's clock is controlled so each test has an independent window without sleeping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "notes.security.requests-per-ip=12", "notes.security.requests-per-account=8",
        "notes.security.requests-per-window=24"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class NotesIntegrationTest {
    private static final String TEAM_A = "11111111-1111-4111-8111-111111111111";
    private static final String TEAM_B = "22222222-2222-4222-8222-222222222222";
    private static final String ALICE = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1";
    private static final String BOB = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2";
    private static final String NOTES = "/api/teams/" + TEAM_A + "/notes";
    private static final EmbeddedPostgres DATABASE = startDatabase();
    private static final AtomicLong LIMIT_TIME = new AtomicLong();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NoteService service;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean(name = "rateLimitClock") Clock rateLimitClock;
    @LocalServerPort int port;

    /**
     * Creates the temporary PostgreSQL process shared by this test class before Spring configures its DataSource.
     *
     * @return a running database that is closed after all tests in this class
     * @throws IllegalStateException if PostgreSQL cannot be started for the suite
     */
    private static EmbeddedPostgres startDatabase() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the test PostgreSQL process", exception);
        }
    }

    /**
     * Redirects Spring's DataSource to the test process instead of any developer or production database.
     *
     * @param registry test property registry applied before the application context starts
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    /**
     * Stops the suite's native database process and releases its temporary data directory.
     *
     * @throws IOException if the managed database cannot be closed cleanly
     */
    @AfterAll
    static void stopDatabase() throws IOException {
        DATABASE.close();
    }

    /**
     * Isolates test scenarios by clearing notes while retaining the shared demo users and memberships.
     */
    @BeforeEach
    void clearNotes() {
        LIMIT_TIME.addAndGet(60000);
        when(rateLimitClock.millis()).thenAnswer(ignored -> LIMIT_TIME.get());
        // This temporary test database must start with no notes for each test.
        //noinspection SqlWithoutWhere
        jdbc.update("DELETE FROM note");
    }

    /**
     * Verifies creation, title normalization, teammate reads and edits, authorship metadata,
     * pagination, and permanent deletion through the HTTP API.
     *
     * @throws Exception if fixture creation, JSON processing, or request execution fails
     */
    @Test
    void teammatesCanCompleteTheNoteLifecycle() throws Exception {
        JsonNode original = create(TEAM_A, "alice", "  Checklist  ", "");
        String path = NOTES + "/" + original.get("id").asText();
        assertThat(original.get("title").asText()).isEqualTo("Checklist");
        assertThat(original.get("content").asText()).isEmpty();
        assertThat(original.get("version").asLong()).isEqualTo(1);
        assertThat(original.get("createdBy").asText()).isEqualTo(ALICE);

        mvc.perform(get(path).with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Checklist"));

        mvc.perform(put(path).with(httpBasic("bob", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Ready", "content":"Reviewed by Bob", "version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.createdBy").value(ALICE))
                .andExpect(jsonPath("$.updatedBy").value(BOB))
                .andExpect(jsonPath("$.createdAt").value(original.get("createdAt").asText()));

        mvc.perform(get(NOTES).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Ready"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        mvc.perform(delete(path).with(httpBasic("bob", "demo-password")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get(path).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isNotFound());
    }

    /**
     * Confirms that team discovery reflects each authenticated user's own memberships.
     *
     * @throws Exception if the simulated HTTP request cannot complete
     */
    @Test
    void listsOnlyTheCallersTeams() throws Exception {
        mvc.perform(get("/api/teams").with(httpBasic("alice", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/teams").with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(TEAM_A));
    }

    /**
     * Confirms that every note endpoint rejects anonymous requests before looking up resources.
     *
     * @param operation endpoint selector; {@code GET_LIST} identifies the collection read
     * @throws Exception if request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET_LIST", "POST", "GET", "PUT", "DELETE"})
    void everyNoteOperationRequiresAuthentication(String operation) throws Exception {
        mvc.perform(noteRequest(operation, UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"team-notes\""))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * Confirms that incorrect and empty passwords produce the standard unauthorized response.
     *
     * @param password invalid password presented for the known demo account
     * @throws Exception if request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", ""})
    void wrongCredentialsAreRejected(String password) throws Exception {
        mvc.perform(get("/api/teams").with(httpBasic("alice", password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    /**
     * Proves that a forged {@code X-User-Id} header cannot replace valid authentication credentials.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void aCallerSuppliedUserIdDoesNotAuthenticateARequest() throws Exception {
        mvc.perform(get("/api/teams").header("X-User-Id", ALICE))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Exercises all note operations as a non-member, verifying a not-found response and unchanged stored content.
     *
     * @param operation collection or individual-note endpoint to attempt without team membership
     * @throws Exception if fixture creation or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET_LIST", "POST", "GET", "PUT", "DELETE"})
    void anotherTeamCannotAccessAnyNoteOperation(String operation) throws Exception {
        JsonNode note = create(TEAM_A, "alice", "Private to Platform", "Original");
        mvc.perform(noteRequest(operation, note.get("id").asText())
                        .with(httpBasic("carol", "demo-password")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Team not found"));
        mvc.perform(get(NOTES + "/" + note.get("id").asText()).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Original"));
    }

    /**
     * Proves that team membership alone cannot make a note valid under the wrong team path.
     * The attempted operation must leave the note in its actual owning team unchanged.
     *
     * @param operation individual-note operation attempted with a substituted team path
     * @throws Exception if fixture creation or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void aNoteCannotBeAccessedThroughAnotherTeamEvenWhenCallerBelongsToBoth(String operation) throws Exception {
        JsonNode note = create(TEAM_B, "alice", "Research", "Unchanged");
        mvc.perform(noteRequest(operation, note.get("id").asText()).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Note not found"));
        assertThat(jdbc.queryForObject("SELECT content FROM note WHERE id = ?", String.class,
                UUID.fromString(note.get("id").asText()))).isEqualTo("Unchanged");
    }

    /**
     * Rejects invalid note fields and malformed JSON without writing any note to the database.
     *
     * @param body invalid JSON payload or malformed input to submit
     * @param field expected validation-error field, or {@code null} for parsing and type failures
     * @throws Exception if request execution or response checking cannot complete
     */
    @ParameterizedTest
    @MethodSource("invalidCreateRequests")
    void invalidCreateRequestsReturnHelpfulErrors(String body, String field) throws Exception {
        var result = mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        if (field != null) {
            result.andExpect(jsonPath("$.fieldErrors." + field).exists());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
    }

    /**
     * Supplies missing, null, oversized, incorrectly typed, unknown-field, and malformed creation inputs.
     *
     * @return payloads paired with a field-error key when Bean Validation should identify a specific field
     */
    static Stream<Arguments> invalidCreateRequests() {
        return Stream.of(
                Arguments.of("{}", "title"),
                Arguments.of("{\"title\":null,\"content\":\"\"}", "title"),
                Arguments.of("{\"title\":\"  \",\"content\":\"\"}", "title"),
                Arguments.of("{\"title\":\"Valid\"}", "content"),
                Arguments.of("{\"title\":\"Valid\",\"content\":null}", "content"),
                Arguments.of("{\"title\":\"" + "x".repeat(201) + "\",\"content\":\"\"}", "title"),
                Arguments.of("{\"title\":\"Valid\",\"content\":\"" + "x".repeat(20001) + "\"}", "content"),
                Arguments.of("{\"title\":123,\"content\":\"\"}", null),
                Arguments.of("{\"title\":\"Valid\",\"content\":false}", null),
                Arguments.of("{\"title\":\"Valid\",\"content\":\"\",\"teamId\":\"spoofed\"}", null),
                Arguments.of("not JSON", null));
    }

    /**
     * Confirms that full replacements require both text fields and a positive integer version,
     * and that rejected replacements do not advance the stored note version.
     *
     * @param body incomplete or invalid update payload
     * @throws Exception if fixture creation or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"Valid\",\"content\":\"\"}",
            "{\"title\":\"Valid\",\"content\":\"\",\"version\":null}",
            "{\"title\":\"Valid\",\"content\":\"\",\"version\":0}",
            "{\"title\":\"Valid\",\"content\":\"\",\"version\":-1}",
            "{\"title\":\"Valid\",\"content\":\"\",\"version\":1.5}",
            "{\"title\":\"Valid\",\"content\":\"\",\"version\":\"1\"}",
            "{\"content\":\"\",\"version\":1}",
            "{\"title\":\"Valid\",\"version\":1}"
    })
    void updatesRequireACompleteValidRepresentation(String body) throws Exception {
        JsonNode note = create(TEAM_A, "alice", "Original", "Unchanged");
        mvc.perform(put(NOTES + "/" + note.get("id").asText()).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT version FROM note", Long.class)).isEqualTo(1);
    }

    /**
     * Rejects negative page numbers, out-of-range sizes, and nonnumeric pagination parameters.
     *
     * @param query invalid pagination query string appended to the collection endpoint
     * @throws Exception if request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?page=no", "?size=no"})
    void invalidPaginationReturns400(String query) throws Exception {
        mvc.perform(get(NOTES + query).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Distinguishes malformed identifiers, which return 400, from well-formed missing resources, which return 404.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void invalidAndMissingIdsHavePredictableResponses() throws Exception {
        mvc.perform(get(NOTES + "/invalid").with(httpBasic("alice", "demo-password")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/teams/invalid/notes").with(httpBasic("alice", "demo-password")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/teams/" + UUID.randomUUID() + "/notes").with(httpBasic("alice", "demo-password")))
                .andExpect(status().isNotFound());
        mvc.perform(put(NOTES + "/" + UUID.randomUUID()).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Missing\",\"content\":\"\",\"version\":1}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Accepts the maximum documented text lengths and proves title length is checked after normalization.
     *
     * @throws Exception if fixture creation or response processing fails
     */
    @Test
    void acceptsDocumentedBoundariesAndTrimsBeforeCheckingTitleLength() throws Exception {
        JsonNode note = create(TEAM_A, "alice", "  " + "x".repeat(200) + "  ", "x".repeat(20000));
        assertThat(note.get("title").asText()).hasSize(200);
        assertThat(note.get("content").asText()).hasSize(20000);
    }

    /**
     * Uses equal update timestamps to verify deterministic ID ordering, page boundaries, and team-specific totals.
     *
     * @throws Exception if fixture creation or request execution fails
     */
    @Test
    void paginationUsesIdAsATieBreakerAndCountsOnlyTheRequestedTeam() throws Exception {
        String low = "00000000-0000-4000-8000-000000000001";
        String high = "00000000-0000-4000-8000-000000000002";
        for (String id : List.of(low, high)) {
            jdbc.update("""
                    INSERT INTO note (id, team_id, title, content, created_by, updated_by, updated_at)
                    VALUES (?, ?, 'Same timestamp', '', ?, ?, '2026-01-01T00:00:00Z')
                    """, UUID.fromString(id), UUID.fromString(TEAM_A), UUID.fromString(ALICE), UUID.fromString(ALICE));
        }
        create(TEAM_B, "carol", "Other team", "");
        mvc.perform(get(NOTES + "?page=0&size=1").with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(high))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(NOTES + "?page=1&size=1").with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(low));
        mvc.perform(get(NOTES + "?page=2&size=1").with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * Reuses an already-consumed version and confirms the later HTTP update cannot overwrite the successful edit.
     *
     * @throws Exception if fixture creation, serialization, or request execution fails
     */
    @Test
    void staleUpdatesReturn409WithoutOverwritingTheWinnersContent() throws Exception {
        JsonNode note = create(TEAM_A, "alice", "Original", "");
        String path = NOTES + "/" + note.get("id").asText();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put(path).with(httpBasic("bob", "demo-password"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(new UpdateNoteRequest("Edit " + attempt, "Winner", 1L))))
                    .andExpect(attempt == 0 ? status().isOk() : status().isConflict());
        }
        mvc.perform(get(path).with(httpBasic("alice", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Edit 0"))
                .andExpect(jsonPath("$.version").value(2));
    }

    /**
     * Starts overlapping transactions with the same expected version to prove the database permits exactly one write.
     * A barrier coordinates the transactions so the test checks the atomic SQL guard rather than only sequential edits.
     *
     * @throws Exception if a worker fails or the coordinated operations exceed their time limits
     */
    @Test
    void twoTransactionsCompetingForTheSameVersionAllowExactlyOneWrite() throws Exception {
        Note note = service.create("alice", UUID.fromString(TEAM_A), new CreateNoteRequest("Original", ""));
        CyclicBarrier barrier = new CyclicBarrier(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> edit = () -> {
                try {
                    transaction.executeWithoutResult(ignored -> {
                        // Establish overlapping transactions before either attempts the conditional write.
                        jdbc.queryForObject("SELECT version FROM note WHERE id = ?", Long.class, note.id());
                        try {
                            barrier.await(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                        service.update("bob", note.teamId(), note.id(), new UpdateNoteRequest("Edited", "", 1L));
                    });
                    return 200;
                } catch (com.bluestaq.notes.api.ApiException exception) {
                    return exception.status().value();
                }
            };
            var first = executor.submit(edit);
            var second = executor.submit(edit);
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(service.get("alice", note.teamId(), note.id()).version()).isEqualTo(2);
    }

    /**
     * Applies production-only migrations to a separate schema, verifies it has no demo accounts,
     * and confirms that a second migration run makes no further changes.
     */
    @Test
    void productionMigrationsDoNotCreateDemoAccounts() {
        Flyway production = Flyway.configure().dataSource(DATABASE.getPostgresDatabase())
                .schemas("without_demo").locations("classpath:db/migration").load();
        assertThat(production.migrate().migrationsExecuted).isEqualTo(1);
        // Flyway creates this schema at runtime, outside IntelliJ's DDL model.
        //noinspection SqlResolve
        assertThat(jdbc.queryForObject("SELECT count(*) FROM without_demo.app_user", Long.class)).isZero();
        assertThat(production.migrate().migrationsExecuted).isZero();
    }

    /**
     * Authenticates against the actual HTTP listener, checks that no session cookie is created,
     * and verifies the anonymous database-backed health endpoint.
     *
     * @throws Exception if the network request or JSON response processing fails
     */
    @Test
    void theRunningServerRespondsOverHttpWithoutCreatingSessions() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            String credentials = Base64.getEncoder().encodeToString("alice:demo-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/api/teams"))
                            .header("Authorization", "Basic " + credentials).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).size()).isEqualTo(2);
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Confirms that SQL metacharacters remain note data during creation, reading, and updates.
     *
     * @param payload SQL-shaped text that must never change the query structure
     * @throws Exception if fixture creation or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"' OR '1'='1' --", "'); SELECT 1; --", "test' UNION SELECT NULL --"})
    void sqlPayloadsAreStoredAsLiteralNoteText(String payload) throws Exception {
        JsonNode created = create(TEAM_A, "alice", payload, payload);
        String path = NOTES + "/" + created.get("id").asText();
        mvc.perform(get(path).with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value(payload))
                .andExpect(jsonPath("$.content").value(payload));
        mvc.perform(put(path).with(httpBasic("bob", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateNoteRequest(payload, payload, 1L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content").value(payload));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class)).isEqualTo(3);
    }

    /**
     * Rejects SQL-shaped usernames rather than allowing them to change the authentication query.
     *
     * @param username attempted SQL injection in the username field
     * @throws Exception if request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"alice' --", "' OR '1'='1' --", "alice' OR 1=1 --"})
    void sqlPayloadsCannotBypassAuthentication(String username) throws Exception {
        mvc.perform(get("/api/teams").with(httpBasic(username, "demo-password")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Preserves markup as plain note data while requiring JSON responses and anti-sniffing headers.
     * This checks the API boundary; a future HTML client must still render note text safely.
     *
     * @param markup candidate stored-XSS payload that must remain JSON string data
     * @throws Exception if fixture creation or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert('xss')</script>",
            "<img src=x onerror=alert(1)>",
            "</script><script>alert(1)</script>",
            "<svg/onload=alert(1)>"
    })
    void markupIsReturnedAsJsonInsteadOfRenderedHtml(String markup) throws Exception {
        JsonNode created = create(TEAM_A, "alice", markup, markup);
        mvc.perform(get(NOTES + "/" + created.get("id").asText()).with(httpBasic("bob", "demo-password")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.title").value(markup)).andExpect(jsonPath("$.content").value(markup));
    }

    /**
     * Refuses HTML content negotiation so a note containing markup cannot become an HTML document.
     *
     * @throws Exception if fixture creation or request execution fails
     */
    @Test
    void noteResponsesCannotBeNegotiatedAsHtml() throws Exception {
        JsonNode created = create(TEAM_A, "alice", "Markup", "<script>alert(1)</script>");
        String response = mvc.perform(get(NOTES + "/" + created.get("id").asText())
                        .with(httpBasic("alice", "demo-password")).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotAcceptable()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("<script>");
    }

    /**
     * Prevents creation requests from assigning server-owned metadata or polymorphic type hints.
     *
     * @param field protected or unrecognized property to inject into a valid creation request
     * @throws Exception if serialization or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"id", "teamId", "createdBy", "updatedBy", "createdAt", "updatedAt", "version", "@class"})
    void creationRejectsMassAssignmentOfServerOwnedFields(String field) throws Exception {
        var body = json.createObjectNode().put("title", "Attempt").put("content", "Data");
        body.put(field, TEAM_B);
        mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
    }

    /**
     * Prevents an otherwise valid update from changing identity, ownership, or authorship metadata.
     *
     * @param field server-controlled property to append to the replacement request
     * @throws Exception if fixture creation, serialization, or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"id", "teamId", "createdBy", "updatedBy", "createdAt", "updatedAt"})
    void updatesRejectMassAssignmentOfServerOwnedFields(String field) throws Exception {
        JsonNode created = create(TEAM_A, "alice", "Original", "Unchanged");
        var body = json.createObjectNode().put("title", "Attempt").put("content", "Changed").put("version", 1);
        body.put(field, TEAM_B);
        mvc.perform(put(NOTES + "/" + created.get("id").asText()).with(httpBasic("bob", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT version FROM note", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM note", String.class)).isEqualTo("Unchanged");
    }

    /**
     * Verifies that browser-simple content types cannot create notes through cross-origin form requests.
     *
     * @param mediaType content type available to browser form or simple cross-origin requests
     * @throws Exception if request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded"})
    void crossOriginSimpleRequestsCannotCreateNotes(String mediaType) throws Exception {
        mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .header("Origin", "https://attacker.example").contentType(mediaType)
                        .content("{\"title\":\"Attempt\",\"content\":\"Changed\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
    }

    /**
     * Verifies that the API does not grant a foreign origin permission to send authenticated JSON requests.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void crossOriginPreflightDoesNotGrantAccess() throws Exception {
        mvc.perform(request(HttpMethod.OPTIONS, NOTES).header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().is4xxClientError())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    /**
     * Rejects XML without parsing an external entity, since this service accepts JSON request records only.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void xmlAndExternalEntityPayloadsAreRejected() throws Exception {
        String externalEntityLocation = "file:///nonexistent-security-audit";
        mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<!DOCTYPE note [<!ENTITY xxe SYSTEM '" + externalEntityLocation + "'>]>"
                                + "<note><title>&xxe;</title><content>Attempt</content></note>"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
    }

    /**
     * Keeps CR/LF input inside the JSON body and uses only server-generated values in response headers.
     *
     * @throws Exception if serialization or request execution fails
     */
    @Test
    void noteTextCannotInjectResponseHeaders() throws Exception {
        var response = mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateNoteRequest("Title\r\nX-Injected: yes", "Text"))))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("X-Injected"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Location")).doesNotContain("\r", "\n", "X-Injected");
    }

    /**
     * Ensures client-visible validation errors do not echo supplied content or reveal implementation details.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void errorResponsesDoNotExposeInputOrInternalDetails() throws Exception {
        String response = mvc.perform(post(NOTES).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"content\":\"private-security-marker\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private-security-marker", "org.springframework", "java.lang", "SELECT ", "password_hash");
    }

    /**
     * Rejects decoded NUL characters as field validation errors on both writing endpoints.
     * Rejected creates leave no notes; rejected updates preserve all existing text, version, and audit metadata.
     *
     * @param method POST for creation or PUT for replacement
     * @param field editable text field containing the NUL character
     * @param invalidText text with a NUL at a boundary, in the middle, or as the entire value
     * @throws Exception if fixture creation, JSON processing, or request execution fails
     */
    @ParameterizedTest
    @MethodSource("nulCharacterRequests")
    void nulCharactersReturnFieldErrorsWithoutChangingData(String method, String field, String invalidText)
            throws Exception {
        JsonNode original = null;
        String path = NOTES;
        if ("PUT".equals(method)) {
            original = create(TEAM_A, "alice", "Original", "Unchanged");
            path += "/" + original.get("id").asText();
        }
        var body = json.createObjectNode().put("title", "Valid").put("content", "Replacement");
        body.put(field, invalidText);
        if (original != null) {
            body.put("version", original.get("version").asLong());
        }
        String response = mvc.perform(request(HttpMethod.valueOf(method), path)
                        .with(httpBasic("alice", "demo-password")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors." + field).value("must not contain NUL (U+0000) characters"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("\\u0000", "org.postgresql", "SQLException", "stackTrace");
        if (original == null) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class)).isZero();
        } else {
            String current = mvc.perform(get(path).with(httpBasic("alice", "demo-password")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(current)).isEqualTo(original);
        }
    }

    /**
     * Covers every editable field on both endpoints with leading, embedded, trailing, and standalone NUL values.
     *
     * @return method, field, and invalid decoded text combinations serialized as escaped JSON by each test
     */
    static Stream<Arguments> nulCharacterRequests() {
        String nul = Character.toString(0);
        return Stream.of("POST", "PUT").flatMap(method -> Stream.of("title", "content")
                .flatMap(field -> Stream.of(nul + "Start", "Mid" + nul + "dle", "End" + nul, nul)
                        .map(text -> Arguments.of(method, field, text))));
    }

    /**
     * Preserves allowed empty, multiline, tabbed, and Unicode content while applying NUL validation on both records.
     *
     * @param text legal plain text that must be stored and returned exactly
     * @throws Exception if fixture creation, serialization, or request execution fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "First line\n\tSecond line\r\nUnicode: café ☕"})
    void nulValidationPreservesOtherPlainText(String text) throws Exception {
        JsonNode created = create(TEAM_A, "alice", "  Release\nchecklist  ", text);
        assertThat(created.get("title").asText()).isEqualTo("Release\nchecklist");
        assertThat(created.get("content").asText()).isEqualTo(text);
        mvc.perform(put(NOTES + "/" + created.get("id").asText()).with(httpBasic("alice", "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateNoteRequest("  Updated\nchecklist  ", text, 1L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Updated\nchecklist"))
                .andExpect(jsonPath("$.content").value(text));
    }

    /**
     * Verifies the byte boundary over a real socket for fixed-length and chunked create/update requests.
     * Multibyte content checks UTF-8 byte counting; title whitespace checks size before normalization.
     *
     * @param chunked whether to omit Content-Length and use chunked transfer
     * @param delta bytes below, at, or above the configured limit
     * @param method POST for creation or PUT for replacement
     * @throws Exception if request construction, transport, or response parsing fails
     */
    @ParameterizedTest
    @MethodSource("bodyLimitRequests")
    void bodyByteLimitCoversKnownAndUnknownLengths(boolean chunked, int delta, String method) throws Exception {
        String path = NOTES;
        if ("PUT".equals(method)) {
            path += "/" + create(TEAM_A, "alice", "Original", "Original").get("id").asText();
        }
        String prefix = "{\"title\":\"";
        String suffix = "Valid\",\"content\":\"é\"" + ("PUT".equals(method) ? ",\"version\":1}" : "}");
        int targetBytes = 262144 + delta;
        String body = prefix + " ".repeat(targetBytes - (prefix + suffix).getBytes(StandardCharsets.UTF_8).length) + suffix;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(targetBytes);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                    "alice:demo-password".getBytes(StandardCharsets.UTF_8)))
                            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                            .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            if (delta > 0) {
                assertThat(response.statusCode()).isEqualTo(413);
                assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM note", Long.class))
                        .isEqualTo("PUT".equals(method) ? 1 : 0);
                if ("PUT".equals(method)) {
                    assertThat(jdbc.queryForObject("SELECT title FROM note", String.class)).isEqualTo("Original");
                }
            } else {
                assertThat(response.statusCode()).isEqualTo("POST".equals(method) ? 201 : 200);
                assertThat(json.readTree(response.body()).get("title").asText()).isEqualTo("Valid");
                assertThat(json.readTree(response.body()).get("content").asText()).isEqualTo("é");
            }
        }
    }

    /**
     * Covers both HTTP body framings at the exact byte boundary for each writing operation.
     *
     * @return create/update cases one byte below, exactly at, and one byte above the size limit
     */
    static Stream<Arguments> bodyLimitRequests() {
        return Stream.of(false, true).flatMap(chunked -> Stream.of(-1, 0, 1)
                .flatMap(delta -> Stream.of("POST", "PUT").map(method -> Arguments.of(chunked, delta, method))));
    }

    /**
     * Blocks failed Basic attempts across changing sources and expires the block without a permanent lockout.
     * A valid password cannot bypass an active budget; another account remains usable.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void authenticationAttemptsAreThrottledAndRecoverAfterTheWindow() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            mvc.perform(get("/api/teams").with(httpBasic("alice", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/teams").with(httpBasic("alice", "demo-password"))
                        .with(request -> { request.setRemoteAddr("192.0.2.10"); return request; }))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        mvc.perform(get("/api/teams").with(httpBasic("bob", "demo-password"))).andExpect(status().isOk());
        LIMIT_TIME.addAndGet(60000);
        mvc.perform(get("/api/teams").with(httpBasic("alice", "demo-password"))).andExpect(status().isOk());
    }

    /**
     * Uses socket peers for source budgets and rejects resets attempted with forwarding headers.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void sourceThrottlingCannotBeBypassedWithForwardingHeaders() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            mvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/teams").with(httpBasic("bob", "demo-password"))
                        .header("X-Forwarded-For", "192.0.2.100").header("Forwarded", "for=192.0.2.101"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        mvc.perform(get("/api/teams").with(httpBasic("bob", "demo-password"))
                        .with(request -> { request.setRemoteAddr("192.0.2.20"); return request; }))
                .andExpect(status().isOk());
    }

    /**
     * Caps aggregate traffic across changing sources while retaining anonymous health checks.
     *
     * @throws Exception if request execution fails
     */
    @Test
    void instanceBudgetLimitsRotatingSourcesButKeepsHealthAvailable() throws Exception {
        for (int attempt = 0; attempt < 24; attempt++) {
            String source = "192.0.2." + attempt;
            mvc.perform(get("/api/teams").with(request -> { request.setRemoteAddr(source); return request; }))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/teams").with(request -> { request.setRemoteAddr("192.0.2.200"); return request; }))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    /**
     * Creates a note through the API and checks the creation status and resource-location header.
     *
     * @param teamId owning team path parameter
     * @param username demo account whose Basic credentials will be used
     * @param title note title, normalized by the request record before serialization
     * @param content note text to preserve
     * @return the created note's JSON representation for later assertions and requests
     * @throws Exception if serialization, request execution, or response parsing fails
     */
    private JsonNode create(String teamId, String username, String title, String content) throws Exception {
        String path = "/api/teams/" + teamId + "/notes";
        String response = mvc.perform(post(path).with(httpBasic(username, "demo-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateNoteRequest(title, content))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    /**
     * Builds a valid request for an authorization scenario without attaching any credentials.
     * Collection requests ignore the note ID, while update requests use expected version 1.
     *
     * @param operation HTTP method, or {@code GET_LIST} to select the collection read endpoint
     * @param noteId individual-note path parameter when the selected operation needs one
     * @return a request builder to which the test may add valid, invalid, or no credentials
     */
    private MockHttpServletRequestBuilder noteRequest(String operation, String noteId) {
        boolean collection = operation.equals("GET_LIST") || operation.equals("POST");
        String path = collection ? NOTES : NOTES + "/" + noteId;
        String method = operation.equals("GET_LIST") ? "GET" : operation;
        var builder = request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON);
        if (operation.equals("POST")) {
            builder.content("{\"title\":\"Attempt\",\"content\":\"Changed\"}");
        } else if (operation.equals("PUT")) {
            builder.content("{\"title\":\"Attempt\",\"content\":\"Changed\",\"version\":1}");
        }
        return builder;
    }
}
