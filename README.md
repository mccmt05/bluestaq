# Bluestaq Team Notes

Christopher Taylor - Bluestaq exercise

Java 21, Spring Boot 3.5.16, Spring MVC, Spring Security, Spring JDBC,
PostgreSQL 16, Flyway, and Maven. Implemented for a 2.5-hour exercise budget.

## Run locally

### With Java, without Docker

Prerequisite: a JDK supported by the selected Spring Boot version (Java 21 is
the build target) with `JAVA_HOME` set. Maven is supplied by the wrapper.

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:test-run
```

Linux/macOS:

```sh
sh ./mvnw spring-boot:test-run
```

This launches the application at `http://localhost:8080` and a local PostgreSQL
process on an automatically selected port. It applies migrations and loads demo
accounts. The first run downloads dependencies and native PostgreSQL binaries.
The bundled test binaries support x64 Windows, Linux, and macOS; other
architectures need the corresponding Zonky PostgreSQL binary dependency.

Stop with Ctrl+C. Data is kept in the ignored `.local-db/` directory across
restarts. This launcher and its embedded PostgreSQL dependency are in test scope
and are excluded from the executable application JAR. The launcher binds HTTP
and PostgreSQL to loopback and is intended only for local development.

In IntelliJ, import `pom.xml` as a Maven project, select a JDK, and run
`LocalDevelopment.main` from the project root. To change the HTTP port:

```powershell
.\mvnw.cmd spring-boot:test-run "-Dspring-boot.run.arguments=--server.port=8081"
```

### With Docker Compose

Prerequisite: Docker with Compose v2. No host JDK is needed.

```sh
docker compose up --build --wait
```

The application is available at `http://localhost:8080`. PostgreSQL has a health
check and the application waits for it before starting. Data lives in the
`notes-data` named volume; `docker compose down` preserves it. The Docker build
packages the application without running tests; run the test command below
separately.

The runtime connection uses `notes_app`, with read access to identities and
memberships and CRUD access to notes. Flyway connects separately as the owner to
apply migrations and grants. Set `DB_APP_PASSWORD` for the runtime role and
`DB_PASSWORD` for the owner; the defaults are public local-demo values.

For a Compose volume created before the runtime-role change, preserve the data
and run the initialization script once before starting the updated app:

```sh
docker compose up -d db
docker compose exec db psql -v ON_ERROR_STOP=1 -U team_notes -d team_notes -f /docker-entrypoint-initdb.d/01-runtime-role.sql
docker compose up --build --wait
```

New volumes run that script automatically. Changing a password environment
variable does not rotate a password already stored in an existing database.

Both startup options are local demos with public credentials. Do not expose
them as a production deployment. Use only one option at a time on port 8080.

### Demo accounts

All accounts use the password `demo-password`.

| Username | Teams                 |
| -------- | --------------------- |
| alice    | Platform and Research |
| bob      | Platform              |
| carol    | Research              |

| Team     | ID                                     |
| -------- | -------------------------------------- |
| Platform | `11111111-1111-4111-8111-111111111111` |
| Research | `22222222-2222-4222-8222-222222222222` |

Verify startup:

```sh
curl http://localhost:8080/health
curl -u alice:demo-password http://localhost:8080/api/teams
```

In Windows PowerShell, use `curl.exe` for these commands. For a complete
create/read/update/conflict/delete walkthrough, open [requests.http](requests.http)
in IntelliJ's HTTP client and run its requests in order.

### Build and test

```powershell
.\mvnw.cmd verify
```

On Linux/macOS use `sh ./mvnw verify`. Tests start temporary PostgreSQL processes
and real Spring application contexts; they do not require Docker or a separately
installed database. Run as a regular user, since PostgreSQL refuses to run as
root. Test data is separate from `.local-db/` and is removed after the tests.

The packaged application is `target/team-notes-0.0.1-SNAPSHOT.jar`. To run it
against your own PostgreSQL database, configure `DB_URL`, `DB_USERNAME`, and
`DB_PASSWORD`, then run:

```sh
java -jar target/team-notes-0.0.1-SNAPSHOT.jar
```

Without the `local` Spring profile, migrations create only the schema. There
are no default application accounts. Provision users with BCrypt password hashes,
teams, and memberships directly in that database; account-management APIs are
outside the exercise scope. Use a separate database from the local demo.

| Setting                  | Default                                       | Purpose                                            |
| ------------------------ | --------------------------------------------- | -------------------------------------------------- |
| `DB_URL`                 | `jdbc:postgresql://localhost:5432/team_notes` | JDBC URL for the packaged app                      |
| `DB_USERNAME`            | `team_notes`                                  | Database username                                  |
| `DB_PASSWORD`            | `team_notes`                                  | Local database password; override outside the demo |
| `SERVER_PORT`            | `8080`                                        | HTTP port                                          |
| `SPRING_PROFILES_ACTIVE` | none                                          | `local` enables demo data migrations               |

The embedded local launcher supplies its own DataSource and does not use the
`DB_*` settings. Compose uses `DB_PASSWORD` for the database owner/Flyway and
`DB_APP_PASSWORD` for the restricted runtime role. For a separately managed
database, provision a restricted runtime role and configure `SPRING_FLYWAY_URL`,
`SPRING_FLYWAY_USER`, and `SPRING_FLYWAY_PASSWORD` for migration credentials.
The Compose-specific grant callback expects a role named `notes_app`.

### Request security limits

Request bodies are capped at **256 KiB before JSON parsing**, including chunked
transfers. Larger bodies return JSON with HTTP **413**. The existing title and
content character limits still apply after parsing.

Before password verification, each 60-second window permits **120 requests per
socket IP**, **60 Basic attempts per username**, and **600 requests across the
application instance**. Both successful and unsuccessful requests consume the
budget; changing a password or forwarding header cannot reset it. Exceeding a
budget returns JSON with HTTP **429** and a `Retry-After` header. Access recovers
when the window expires. Anonymous GET `/health` probes remain available.

Configure these budgets under `notes.security` in `application.yml` (or Spring
environment overrides, such as `NOTES_SECURITY_MAXREQUESTBYTES`). Counters
retain at most 10,000 combined source/account identities and reject new identities
if full. They are in memory per process and reset on restart. Multiple replicas
need coordinated limits at a trusted proxy or shared store. Fixed windows can
allow bursts near a boundary. Forwarding headers are disabled; behind a proxy,
the socket IP budget is shared by clients using that proxy until a trusted edge
limiter is configured. Tune the budgets for the actual deployment.

If Maven reports a `PKIX path building failed` error on a Windows network whose
certificates are trusted by Windows, use the Windows trust store for that shell:

```powershell
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE'
```

Certificate validation remains enabled. This is an environment setting, not an
application requirement.

## Goal

Build a small REST service where authenticated members of a team can capture,
read, edit, and delete shared notes. Prioritize clear behavior, team isolation,
durable storage, and a straightforward local setup.

## First-version scope

- A user can belong to multiple teams and list their own teams.
- Every note belongs to exactly one team. All members have the same permissions:
  they can read, create, edit, and delete any note in that team.
- Notes contain a title and plain-text content. Empty content is allowed so a
  user can capture a title quickly.
- List notes within a team with pagination and a deterministic sort order.
- Persist notes across application restarts.
- Reject an update based on an outdated note version to prevent lost edits.
- Provide automated tests, database migrations, and documented local startup.

The initial dataset supplies users, teams, and memberships. Creating accounts,
managing memberships, and inviting teammates are outside this version's scope.

## Authentication and authorization

Use HTTP Basic authentication for the exercise, with documented demo
accounts and hashed stored passwords. Seed demo data only in an explicit local
development configuration. Do not accept a caller-supplied user ID as identity.
Production use would require HTTPS and a production identity setup.

Authentication is stateless: clients send an `Authorization` header on each
request. There are no login sessions or cookies. CSRF protection is disabled for
this JSON API, which is intended for explicit-header API clients. A future
browser frontend must revisit CSRF and credential storage. CORS is not enabled.

Every operation checks the authenticated user's team membership. A note ID is
never sufficient to grant access. Resolve notes using both the team ID and note
ID, after checking membership.

- Missing or invalid credentials return `401 Unauthorized`.
- A team the caller cannot access returns `404 Not Found`.
- A missing note, or a note outside the requested team, returns `404 Not Found`.

Using the same not-found response avoids disclosing other teams' resources.

## Data model

Use PostgreSQL with Flyway migrations and database-enforced foreign keys.
Spring JDBC's `JdbcClient` executes parameterized SQL; there is no ORM.

| Entity     | Fields and constraints                                                                                                          |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------- |
| User       | UUID primary key, unique username, password hash                                                                                |
| Team       | UUID primary key, name                                                                                                          |
| Membership | Team ID and user ID foreign keys; unique pair                                                                                   |
| Note       | UUID primary key, team ID, title, content, created-by user ID, last-updated-by user ID, created-at, updated-at, integer version |

IDs, timestamps, authorship, team assignment, and versions are controlled by the
server. Timestamps use UTC. Notes cannot move between teams in this version.
An index supports team-scoped ordering by updated time and ID.

## REST contract

All `/api` endpoints require authentication and use JSON. IDs are UUIDs. Paths
below are relative to `/api`. `GET /health` is unauthenticated, checks database
connectivity, and returns `{"status":"UP"}` when healthy.

| Method | Path                                   | Behavior                                             | Success               |
| ------ | -------------------------------------- | ---------------------------------------------------- | --------------------- |
| GET    | `/teams`                               | List the caller's teams                              | `200`                 |
| GET    | `/teams/{teamId}/notes?page=0&size=20` | List team notes                                      | `200`                 |
| POST   | `/teams/{teamId}/notes`                | Create a note from title and content                 | `201` with `Location` |
| GET    | `/teams/{teamId}/notes/{noteId}`       | Read a note                                          | `200`                 |
| PUT    | `/teams/{teamId}/notes/{noteId}`       | Replace title and content using the expected version | `200`                 |
| DELETE | `/teams/{teamId}/notes/{noteId}`       | Permanently delete a note                            | `204`                 |

List notes by `updatedAt DESC, id DESC`. Each page and its total count use one
repeatable-read snapshot. The page size defaults to 20 and must be
between 1 and 100; the page number must be nonnegative. Return an object with
`items`, `page`, `size`, and `totalElements`. Offset pagination is sufficient for
small teams; edits between requests may change which page contains a note.

Create request:

```json
{
  "title": "Release checklist",
  "content": "Verify migrations before deployment."
}
```

Update request:

```json
{
  "title": "Release checklist",
  "content": "Verify migrations and check service health.",
  "version": 1
}
```

Create and read responses include `id`, `teamId`, `title`, `content`,
`createdBy`, `updatedBy`, `createdAt`, `updatedAt`, and `version`. Updates return
the same representation with the new version. List items use this representation
as well to keep the first API simple.

### Validation and errors

- Trim titles; require 1-200 characters after trimming.
- Require a string content field, allowing empty content up to 20,000 characters.
- Reject NUL (U+0000) characters in titles and content on both create and update,
  returning a field-specific `400` error before the database write.
- Require title, content, and a positive integer version for updates; there is
  no partial update. New notes start at version 1.
- Reject unknown JSON properties and values of the wrong JSON type. Length
  limits use Java string length (UTF-16 code units).
- Reject invalid IDs, malformed JSON, invalid pagination, and invalid fields
  with `400 Bad Request` and useful field-level details when applicable.
- Return `409 Conflict` for an outdated update version. The client must fetch
  the latest note and decide how to reconcile its changes.
- Enforce the version check atomically in the database transaction; checking
  the version only before writing is insufficient.
- Delete is unconditional after authorization. Deleting an already missing note
  returns `404`; there is no trash or restore operation.
- Use a consistent error object with `status`, `code`, `message`, and optional
  `fieldErrors`. Never expose stack traces or database details.

## Implementation shape

Keep one deployable application with request handlers, an application/service
layer for authorization and transactions, and database access through the
framework's persistence facilities. Avoid introducing generic repository
abstractions or separate services without a concrete need.

Docker Compose starts the application and PostgreSQL with health checks and a
persistent database volume. Flyway applies migrations on startup. The application
container runs as an unprivileged user. Configuration uses environment variables.
The test-scoped local launcher provides a second option when Docker is unavailable.

## Three design choices

1. **Team-owned notes with equal member permissions.** Team ownership gives the
   sharing requirement a concrete meaning. Keeping permissions uniform makes
   access rules easy to explain and test. It does not support private notes,
   read-only members, or individual note sharing.
2. **Relational storage with migrations.** Memberships and ownership have clear
   relationships that benefit from foreign keys and transactions. PostgreSQL
   makes persistence behavior explicit, at the cost of requiring a database
   process for local execution. Explicit JDBC keeps the small schema and atomic
   version update visible without introducing ORM mapping or lifecycle behavior.
3. **Full note replacement with optimistic concurrency.** A title/content update
   and integer version make edits predictable and prevent silent lost updates.
   Conflicts require client intervention; this does not provide live editing or
   automatic merging.

## Verification

The automated suite has 122 passing tests/cases, including PostgreSQL integration checks, covering:

- A member can create, list, read, update, and delete a team note.
- Teammates can collaborate on the same note.
- Another team's member cannot list, create, read, update, or delete notes in
  that team, including attempts that substitute a known note ID.
- Requests without valid authentication are rejected.
- Invalid titles, content, IDs, and pagination produce the documented errors.
- NUL characters in either text field return validation errors on create/update;
  rejected updates preserve the entire note, while legal whitespace and Unicode remain intact.
- Competing updates using the same version allow one write and reject the other.
- Pagination has deterministic ordering, including equal updated timestamps.
- Migrations work on an empty database, do not seed production accounts, and
  can run again without reapplying changes.
- Notes survive actual application and PostgreSQL shutdown/restart cycles.
- A running HTTP server authenticates requests without creating a session.
- SQL injection payloads, markup returned as JSON, server-owned metadata rejection,
  cross-origin request examples, XML rejection, and response-header injection.
- Fixed-length and chunked HTTP body limits at the byte boundary for creates and updates.
- Source/account/global request budgets, automatic recovery, concurrent reservations,
  bounded counter storage, and forwarding-header spoofing attempts.
- Separate migration and runtime roles, successful API persistence with restricted
  permissions, and rejected runtime schema/account changes.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for reproduced security gaps,
dependency advisory assessment, and the scope of the checks performed.

`verify` and executable JAR packaging were checked on Windows x64 with Temurin
JDK 21. Docker execution has not been verified on
this workstation because Docker is not installed.

## Deliberately outside this version

No frontend, registration, invitations, roles, private notes, search, tags,
attachments, rich text, revision history, soft deletion, live collaboration,
background jobs, or separate microservices.

## If more time were available

Reassess priorities based on actual usage. Likely next steps are production
identity integration and membership administration, revision history to recover
edits or deletions, and title/content search. Before exposing the service
publicly, add operational configuration for backups, coordinated proxy limits, and
structured monitoring. Cursor pagination can replace offset pagination if note
volume or page drift becomes a problem.

## Project layout

```text
src/main/java/com/bluestaq/notes/
  api/       Shared errors and health endpoint
  config/    Authentication and JSON configuration
  note/      Note requests, controller, service, and SQL repository
  team/      Team listing and membership checks
src/main/resources/db/
  migration/ Schema migrations for every environment
  local/     Demo data, enabled only by the local profile
src/test/java/com/bluestaq/notes/
  NotesIntegrationTest.java   API, authorization, SQL, and concurrency tests
  LocalDevelopment.java       Local launcher with persistent PostgreSQL
  LocalDevelopmentTest.java   Application/database restart test
```

Framework references: [Spring Boot system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html),
[test-run Maven goal](https://docs.spring.io/spring-boot/3.5/maven-plugin/api/java/org/springframework/boot/maven/TestRunMojo.html),
and [Zonky embedded PostgreSQL](https://github.com/zonkyio/embedded-postgres).
