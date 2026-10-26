# Security review

Reviewed on October 25, 2026, against implementation commit `925c904` and the
additional security regression tests in this working tree. The original findings
are preserved below, followed by their remediation status. S1, S2, S3, and D2 have
been addressed in the working tree; the other conditions remain open.

## Results

The checked endpoints did not expose SQL injection, executable HTML responses,
cross-team note access, metadata mass assignment, XML entity processing, or
response-header injection. The review did identify an HTTP request-size gap,
missing authentication throttling, and a reproducible invalid-input error path.
The demo deployment also needs production-specific security configuration before
it is exposed beyond loopback.

The full Maven verification passed on Temurin JDK 21 after remediation:
**122 tests/cases, zero failures, zero errors**. The initial review added 31
security cases; remediation added another 41. Separate bounded
HTTP probes used a fresh temporary PostgreSQL instance and application context;
they did not access the developer's `.local-db` database.

## Remediation status

- **S1 fixed:** [RequestBodyLimitFilter](src/main/java/com/bluestaq/notes/config/RequestBodyLimitFilter.java)
  checks declared length and reads at most 256 KiB plus one byte before MVC/JSON
  parsing. Excess bytes produce JSON with HTTP 413. Real HTTP tests cover
  fixed-length and chunked POST/PUT bodies one byte below, at, and above the cap,
  with multibyte text and pre-normalization title whitespace. Oversized writes
  leave existing data unchanged.
- **S2 fixed for a single instance:** [RequestThrottlingFilter](src/main/java/com/bluestaq/notes/config/RequestThrottlingFilter.java)
  reserves source, Basic username, and instance budgets before credential lookup
  or BCrypt. Defaults per 60 seconds: 120 requests/source, 60/account, 600 total.
  Both accepted and failed attempts consume the budget. JSON HTTP 429 responses
  include Retry-After; blocks expire automatically. Storage is bounded to 10,000
  identities and fails closed if full. Tests cover rejected downstream work,
  parallel reservations, spoofed forwarding headers, expiry, and anonymous health
  availability. Budgets are configurable under `notes.security`.
- **S3 fixed:** both [CreateNoteRequest](src/main/java/com/bluestaq/notes/note/CreateNoteRequest.java)
  and [UpdateNoteRequest](src/main/java/com/bluestaq/notes/note/UpdateNoteRequest.java)
  reject decoded U+0000 through Bean Validation before the controller calls the
  service. Responses use HTTP 400 with an error for the affected field. Sixteen
  regression cases cover leading, embedded, trailing, and standalone NUL in each
  field on both endpoints. Rejected creates leave no notes, and rejected updates
  preserve the full saved representation, including version and audit metadata.
  Two additional cases retain empty content, multiline/tabbed text, and Unicode.
- **D2 fixed in Compose:** a new `notes_app` role can read identity/membership
  tables and perform note CRUD. Separate Flyway credentials perform migrations
  and the grant callback. The Compose initialization SQL and callback were
  exercised against temporary PostgreSQL: runtime authentication lookup and note
  CRUD passed, while DDL, user/membership changes, and schema-history access were
  denied. Docker execution itself remains unverified. See the README's one-time
  instructions for existing Compose volumes; no existing data was deleted.

Rate counters reset on process restart and are local to each instance. Multiple
replicas need coordinated edge/shared limits. Fixed windows permit bursts at a
boundary, and the application does not claim protection against a distributed
volumetric attack. Forwarding headers are disabled: behind a proxy, the per-IP
budget applies to the socket peer until trusted edge limits are configured.
Anonymous health probes remain exempt. Body reads are synchronous and bounded;
network timeouts and connection limits still belong in the deployment policy.

Production HTTPS/identity configuration and the dependency
upgrade remain follow-ups. The test-scoped embedded launcher still uses its local
development database owner. Migration credentials remain available to Flyway at
startup; the role separation limits SQL privileges of runtime queries, not an
attacker who already controls the application process.

## Confirmed application gaps

### S1. No application-level JSON request byte limit

**Priority: medium for a remotely reachable service.**

**Status: fixed; the evidence below describes the original implementation.**

Evidence: the real HTTP listener accepted a **2,097,182-byte** creation request
with HTTP 201. Its title contained a valid word surrounded by large amounts of
whitespace, which the request constructor stripped before validating the title's
length. Both a request with Content-Length and a chunked request were accepted.

The limits in [CreateNoteRequest](src/main/java/com/bluestaq/notes/note/CreateNoteRequest.java)
and [UpdateNoteRequest](src/main/java/com/bluestaq/notes/note/UpdateNoteRequest.java)
protect normalized field sizes after JSON parsing. They do not bound the bytes
read or the memory used before validation. There is no separate JSON body limit
in the original [application.yml](src/main/resources/application.yml).

Repeated large authenticated requests can increase parsing, allocation, and
normalization work. The review did not attempt heap exhaustion or sustained load.

Recommended fix: enforce a small HTTP body budget, for example 256 KiB, before
deserialization and return 413 when exceeded. Count bytes actually read so a
chunked request cannot bypass the check. Apply corresponding limits at the
deployment proxy, including decompressed sizes if compression is supported.
Retain the existing field-level validation as a separate control.
[OWASP REST guidance](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html#input-validation).

### S2. No authentication or request throttling

**Priority: medium; more significant if publicly exposed.**

**Status: fixed for a single instance; the following describes the original implementation.**

[SecurityConfiguration](src/main/java/com/bluestaq/notes/config/SecurityConfiguration.java)
uses database-backed HTTP Basic authentication and BCrypt. At review time, no
application rate limiter, login backoff, or configured reverse-proxy limiter was
present. Basic credentials are checked on each authenticated request.

The bounded HTTP probe submitted eight wrong passwords for the same account.
Each response was 401, followed by a successful valid login with HTTP 200. That
sample alone does not establish a universal threshold; the absence of an
implemented limiter is established by the code/configuration review.

This permits repeated online guessing and repeated password-hash work. The demo
password is already public, so guessing is chiefly a concern once real users are
provisioned. No password cracking or sustained availability test was performed.

Recommended fix: add bounded per-source and per-account throttling/backoff,
monitor repeated failures, and return 429 where appropriate. Avoid a permanent
account-lockout design that lets an attacker lock out other users. Treat forwarded
client-address headers as trusted only when supplied by a controlled proxy.
[OWASP authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling).

### S3. NUL characters pass validation and produce HTTP 500

**Priority: low; input-validation and error-path issue.**

**Status: fixed; the evidence below describes the original implementation.**

Reproduced for both title and content:

```http
POST /api/teams/11111111-1111-4111-8111-111111111111/notes
Content-Type: application/json
Authorization: Basic <valid demo credentials>

{"title":"Valid","content":"a\u0000b"}
```

Original result: HTTP 500 with the generic internal-server-error body. PostgreSQL
rejects the decoded NUL character after the request has passed Bean Validation.
The same input placed inside a nonblank title also returns 500.

The response did not expose SQL or stack traces. However, a client
could repeatedly trigger an avoidable database exception and server stack-trace
logging through [ApiExceptionHandler](src/main/java/com/bluestaq/notes/api/ApiExceptionHandler.java).
This is not evidence of SQL execution or data disclosure.

Applied fix: reject U+0000 in both request records, on create and update,
before reaching persistence, returning 400 with a field-specific validation message.
PostgreSQL documents that character types cannot store code zero.
[PostgreSQL character-type documentation](https://www.postgresql.org/docs/16/datatype-character.html).

## Deployment findings and conditions

### D1. Public demo credentials and plain HTTP are not a production identity setup

**Priority: high if the demo configuration is exposed to untrusted networks.**

[compose.yaml](compose.yaml) explicitly enables the local profile, which installs
public `alice`, `bob`, and `carol` accounts using `demo-password`. The database
password defaults to `team_notes`. The HTTP probe confirmed that Basic
authentication succeeds over plain HTTP. The packaged application's default
configuration does not configure TLS or enforce an HTTPS-only production policy.

The Compose host bindings and the development launcher default to loopback;
production migrations do not create demo users without the local profile. Those
are meaningful safeguards, so this review does not classify the default demo as
an already exposed public credential leak.

Recommended fix before public deployment: use HTTPS, provision non-demo users,
keep the local profile disabled, require external secrets rather than password
fallbacks, and enforce the intended production network/TLS policy. Never send
real Basic credentials over an untrusted plaintext connection.
[OWASP REST transport guidance](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html#https).

### D2. The Compose application uses the PostgreSQL bootstrap superuser

**Priority: medium as defense in depth.**

**Status: fixed in Compose; the following describes its original configuration.**

Originally Compose set `POSTGRES_USER: team_notes` and supplied that same user to the
application as `DB_USERNAME`. The official PostgreSQL image creates
POSTGRES_USER with superuser privileges. If another issue allows arbitrary
database commands, that role unnecessarily expands the possible damage.
[Official image documentation](https://github.com/docker-library/docs/blob/master/postgres/README.md#postgres_user).

Recommended fix: separate migration/owner credentials from runtime credentials.
Grant the runtime role only the table operations the API requires, without
superuser, role creation, database creation, or schema ownership. Docker was not
available for live verification of the Compose database role; this finding is
based on its configuration and the image's documented initialization behavior.

### D3. CSRF protection must be reconsidered for browser clients

CSRF is disabled, and HTTP Basic authentication can be sent automatically by a
browser. Stateless authentication alone does not eliminate CSRF exposure.
[Spring Security guidance](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html#csrf-and-stateless-browser-applications).

The current tested API accepts JSON note bodies, rejects browser-simple
text/plain and form content types, and does not authorize a foreign-origin JSON
preflight. No working cross-site write was demonstrated. These results must not
be generalized to future form endpoints, permissive CORS settings, or a browser
frontend. Revisit the authentication model and CSRF controls when adding those.

## Dependency advisory assessment

Resolved production dependencies include Spring Framework **6.2.19** and Spring
Security **6.5.11**. IntelliJ reports version-based advisories against these
versions. For the advisories reviewed below, the relevant features were absent
from this API or their exploitation prerequisites were not established. This
reduces present reachability; it does not make those dependency versions patched.

| Advisories                                                                                                                 | Required feature or condition                                           | Assessment of this service                                    |
| -------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------- |
| [CVE-2026-47891](https://spring.io/security/cve-2026-47891/), [CVE-2026-47885](https://spring.io/security/cve-2026-47885/) | WebFlux XML/Aalto or streaming multipart input                          | MVC JSON endpoints; neither input path configured             |
| [CVE-2026-47889](https://spring.io/security/cve-2026-47889/)                                                               | Jetty reactive response cookies                                         | Embedded Tomcat; no session cookies                           |
| [CVE-2026-47883](https://spring.io/security/cve-2026-47883/)                                                               | Broadly configured UrlHandlerFilter redirect                            | No UrlHandlerFilter configuration found                       |
| [CVE-2026-59281](https://spring.io/security/cve-2026-59281/)                                                               | HTML rendering of EscapedErrors field errors                            | Plain binding results are serialized as JSON; no HTML views   |
| [CVE-2026-59314](https://spring.io/security/cve-2026-59314/)                                                               | Untrusted Content-Disposition values or outbound multipart construction | No file download or outbound multipart endpoint               |
| [CVE-2026-59282](https://spring.io/security/cve-2026-59282/)                                                               | Property binding into self-populating lists                             | JSON request records contain scalar fields only               |
| [CVE-2026-47884](https://spring.io/security/cve-2026-47884/), [CVE-2026-47887](https://spring.io/security/cve-2026-47887/) | XsltView or UrlFileNameViewController                                   | No view-rendering controllers configured                      |
| [CVE-2026-47890](https://spring.io/security/cve-2026-47890/), [CVE-2026-59313](https://spring.io/security/cve-2026-59313/) | SSE fragments or functional SSE endpoints                               | No SSE endpoints                                              |
| [CVE-2026-59283](https://spring.io/security/cve-2026-59283/), [CVE-2026-47886](https://spring.io/security/cve-2026-47886/) | Compiled SimpleEvaluationContext or user-controlled SpEL                | No request-supplied expressions are evaluated                 |
| [CVE-2026-47841](https://spring.io/security/cve-2026-47841/)                                                               | WebAuthn with required verification and distributed sessions            | HTTP Basic and stateless sessions; WebAuthn is not configured |
| [CVE-2026-59276](https://spring.io/security/cve-2026-59276/)                                                               | Specific Digest/token/password4j comparisons                            | BCrypt is used; the affected components are not configured    |
| [CVE-2026-47842](https://spring.io/security/cve-2026-47842/)                                                               | AesBytesEncryptor with the affected CBC initialization                  | No use of that encryptor found                                |

Spring's advisories list Framework 6.2.20 and Security 6.5.12 fixes as
enterprise-only, with open-source fixes in newer major lines. A supported
Spring Boot upgrade should be a planned follow-up rather than independently
mixing incompatible Spring major versions into this application.
[Framework fix availability](https://spring.io/security/cve-2026-59281/),
[Security fix availability](https://spring.io/security/cve-2026-47841/).

**Test-only dependency:** `json-smart:2.6.0` is flagged for CVE-2026-113602. The
upstream report describes a strict-parser validation bypass involving U+001A.
It is used by test JSONPath support and is not packaged in the production JAR.
Do not use that parser as a security validator for hostile data; monitor the
upstream fix. This advisory is not an HTTP attack path against the current
Jackson-backed API.
[Upstream vulnerability report](https://github.com/netplex/json-smart-v2/issues/302).

## Controls checked successfully

| Area                                     | Evidence and outcome                                                                                                                                  |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| SQL injection                            | All user values use JDBC parameters; SQL-shaped usernames return 401 and note payloads remain literal text on create/read/update                      |
| Stored/reflected XSS at the API boundary | Script, SVG, image-handler, and closing-script payloads remain JSON strings; responses use application/json and nosniff; HTML negotiation returns 406 |
| Authorization / IDOR                     | Existing tests cover every operation as another team's member and note-ID substitution even when the caller belongs to both teams                     |
| Mass assignment / type hints             | Creation rejects all server-owned metadata and @class; updates reject ownership, identity, timestamp, and authorship overrides                        |
| CSRF / CORS examples                     | Tested foreign-origin simple form/text requests and JSON preflight cannot create notes                                                                |
| XXE                                      | XML with an external-entity declaration returns 415 without a note being written                                                                      |
| Header injection                         | CR/LF in note text does not create response headers; Location uses a generated UUID                                                                   |
| Error disclosure                         | Validation and reproduced database errors do not return input content, SQL, or stack traces                                                           |
| Authentication basics                    | Missing credentials, wrong passwords, forged user-ID headers, SQL-shaped usernames, and malformed Basic encoding are rejected                         |
| URI variants                             | Tested encoded NUL, encoded slash/traversal, and health-prefix traversal requests did not bypass authentication                                       |
| Sensitive response caching               | Authenticated HTTP responses include no-store and do not create session cookies                                                                       |
| Secret review                            | Recognized key/private-key patterns were checked in project source/config; the discovered credentials are documented public demo values               |

Keeping markup as data is appropriate for a plain-text notes API. A future UI
must escape text in its output context or use safe text-rendering APIs; inserting
stored note content with raw innerHTML would create a stored-XSS vulnerability.
Server-side JSON serialization alone cannot secure an unsafe HTML consumer.
[OWASP XSS guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html).

No production endpoint accepts file paths, fetches client-supplied URLs, invokes
OS commands, evaluates client expressions, or enables polymorphic Jackson typing
in the reviewed code. No corresponding application-level path traversal, SSRF,
command injection, or unsafe object-deserialization sink was found.

## Reproduction and boundaries

- Run `./mvnw.cmd verify` on Windows, or `sh ./mvnw verify` on Linux/macOS.
- New regression cases are in
  [NotesIntegrationTest](src/test/java/com/bluestaq/notes/NotesIntegrationTest.java),
  [RequestRateLimiterTest](src/test/java/com/bluestaq/notes/config/RequestRateLimiterTest.java),
  and [DatabasePrivilegesTest](src/test/java/com/bluestaq/notes/DatabasePrivilegesTest.java).
- Captured pre-remediation probe observations are in `target/security-probe-results.json`
  in this working tree. The generated target directory is intentionally ignored
  by Git. The wire-size, NUL, and repeated-login outcomes are recorded above so
  the findings survive cleanup of generated artifacts.
- Dependency versions were resolved using Maven's dependency tree; source
  analysis used IntelliJ inspections plus manual review and upstream advisories.
- Container OS/image scanning, a deployed reverse proxy/TLS configuration, a
  browser frontend, and sustained denial-of-service testing were outside the
  checks performed. No frontend exists in this project.
- Production source/configuration now includes the remediations above. No changes
  were committed automatically.

## Suggested remediation order

1. Retain the request limit, bounded throttling, and NUL validation regression coverage.
2. Tune the budgets and add coordinated edge limits appropriate to the deployment.
3. Before nonlocal deployment, require HTTPS, disable demo provisioning/default secrets,
   and use the restricted database runtime role.
4. Plan and test the framework upgrade, retain these regression cases, and repeat
   dependency/image scanning in the actual deployment environment.
