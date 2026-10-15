# Execution Plan: AdInsight v1

Date: 2026-10-14

## Status

Active — initial foundation slice, followed by the MVP backlog in spec section 21.

## Current slice — 2026-10-15

User authority: continue implementation; organize backend using DDD, SOLID and
clean code. Preserve existing HTTP behavior while moving the Maven application
to `backend/`. Contexts: identity/access, advertising metadata and ingestion.
Domain/application use plain Java; Spring/JPA/JDBC/HTTP sit in adapters.

- [x] Move backend and refactor account/identity dependencies inward.
- [x] Implement ingestion model, tenant-authorized submit/read and durable
  MySQL idempotency, run sequence allocation, lease ownership and recovery.
- [x] Add architecture checks and behavior tests (including negative proof).
- [x] Update OpenAPI/setup/architecture, run native validation and record limits.

Actual GCS/BigQuery adapters and a complete source-to-cloud worker remain a
subsequent slice; do not claim the queue processes jobs without an adapter.
Podman was already running at inspection: leave it running after tests.

### Evidence — 2026-10-15

- `rtk proxy powershell -NoProfile -File scripts/validate.ps1 -Integration`
  passed: 13 Python tests, 14 backend unit/architecture tests and 7 MySQL/HTTP
  integration tests (34 total, no skipped tests).
- Actual MySQL 8.4.8 executed V1 + V2, JPA validation, concurrent duplicate
  submissions, exclusive claims, expired-token/generation rejection, retry cap
  and UTC timestamp checks. FETCH failures do not write publication/checkpoints.
- HTTP proof covers 202/Location, duplicate ID, changed-scope 409, cross-tenant
  404, role denial, missing-CSRF 403 and valid repeated POST. A CSRF session
  cookie alone does not authenticate a user (401).
- Corrected Basic/CSRF interaction after observing that implicit session
  authentication cleared the token after POST. Authentication now remains
  request-scoped; CSRF session state remains protected. Temporary diagnostics removed.
- ArchUnit positive/negative fixtures pass; Spotless checks 45 Java source
  files. OpenAPI YAML parses and all 31 local references resolve.
- JAR: `backend/target/adinsight-0.1.0-SNAPSHOT.jar`; V2 migration is packaged.
  Local checks passed; CI and merge protection remain unconfigured/unverified.

The DDD/ingestion queue slice is complete. Next connect source fetch/normalization
handlers, then implement GCS/BigQuery adapters, persisted remote job reconciliation
and the warehouse publisher fence before allowing publication/checkpoint completion.

## Structure readability follow-up — 2026-10-15

User approved making service/entity/DTO/controller/config roles explicit after
reviewing the DDD structure. This is an internal refactor; product policies,
database schema, endpoints and JSON fields remain unchanged.

- [x] Move framework-free models to context-owned `domain/model` packages.
- [x] Separate application `service` and `port`; use explicit service names.
- [x] Separate HTTP `controller`, `dto` and pure `mapper` packages; extract all
  nested controller records into top-level request/response DTO files.
- [x] Group Spring composition under `infrastructure/config`.
- [x] Explain the concrete request flow and JPA entity/domain model distinction.
- [x] Validate a clean backend build, architecture checks and HTTP/MySQL behavior.

Evidence: backend `clean verify` completed successfully with 14 unit/architecture
and 7 HTTP/MySQL tests; Python's existing suite passed all 13 tests (34 total,
zero skipped). Spotless checked 52 Java files; the packaged JAR was rebuilt.
The initial root invocation combined `spotless:apply clean verify`: backend
succeeded, but the aggregator could not resolve the backend-only Spotless plugin.
Run formatting with `-pl backend`; the separate `mvnw.cmd -N -B -ntp verify`
passed for the aggregator. `git diff --check` also passed.
HTTP routes/JSON and database migrations were preserved. Cloud worker work remains
pending as recorded above.

## Outcome and authority

Build the platform in [spec-v1.md](../../../spec-v1.md). The first slice makes
Java compilation, tenant authorization, database migrations, deterministic raw
fixtures and snapshot correctness independently verifiable.

## Approach

1. Install the pinned Harness core and replace the generic navigation map with consumer paths.
2. Scaffold Java 21/Spring Boot with Maven Wrapper; implement tenant-scoped account reads.
3. Add Flyway metadata/outbox/job schema with composite tenant constraints.
4. Generate raw fixtures with immutable manifests; normalize decimals and validate complete snapshots.
5. Supply BigQuery DDL and a transaction for campaign fact/mart replacement.
6. Prove replay, correction, empty/partial snapshots, currency, tenant, and authorization boundaries.
7. Continue with GCS/BigQuery job reconciliation, Dataform orchestration, ES projection, then UI/export/forecast.

## Risks and recovery

- Docker CLI is absent, but the existing Podman VM supplies a compatible socket.
  On 2026-10-14 it started stopped and was restored afterward. On 2026-10-15 it
  was already running; leave it running after tests.
- Cloud credentials are not provided: cloud scripts are prepared but runtime correctness remains unverified.
- Fixture output is written to a new run path; failed runs cannot overwrite previous raw evidence.
- Existing spec and the nested harness source are preserved. No external resources are provisioned.

## Progress

- [x] Read spec and harness authority; install core 0.1.10 with SHA-256 and version checks.
- [x] Backend, schema, and tenant reads.
- [x] Fixture generator, normalizer, and warehouse SQL assets (cloud execution pending).
- [x] Validation and documented setup for the foundation slice.
- [ ] Cloud worker, search, reports, forecast, bidding, UI and benchmarks.

## Decisions

- Use Spring Boot 3.5.16 (Java 21 compatible) and Maven 3.9.12, pinned.
- Override Boot's Flyway 11.7.2 with 11.15.0 for MySQL 8.4 support, following
  [upstream evidence](https://github.com/flyway/flyway/issues/4168). Core and
  MySQL adapter resolve to the same version; revalidate migration after the change.
- No existing auth implementation exists. HTTP Basic with database-backed BCrypt principals
  is the initial access mechanism; no default user or password is installed.
- Local warehouse verification is an isolated SQLite reference model, not an analytics
  adapter for the API. Production analytics remains BigQuery as required by the spec.

## Validation

2026-10-14:

- 13 Python tests pass: tenant/account/date/currency mapping, checksum/path gates,
  exact NUMERIC/Parquet, duplicate conflicts, replay, stale ordering, partial and empty runs.
- 6 MockMvc tests pass: authenticated membership, malformed header, cross-tenant
  denial, safe account DTO and dependency failure responses.
- MySQL 8.4.8 Testcontainers test passed via Podman, proving Flyway DDL,
  Hibernate validation, composite tenant FK and outbox version uniqueness.
- HTTP integration also passed with real BCrypt credentials: account read 200
  in member tenant, 403 in the other tenant, request ID returned.
- Local demo: `.local/bootstrap-final-20261014/evidence.json`; 1,200 baseline
  rows, 16 immutable raw runs, 9 Parquet runs. VND cost remains 488225000 after
  correction; conversions change 1952.9 -> 1970.4; empty snapshot yields zero/null KPIs.
- `rtk proxy powershell -NoProfile -File scripts/validate.ps1 -Integration`
  passed with Flyway 11.15.0: 13 Python + 6 MockMvc + 1 MySQL/HTTP integration
  test, JAR packaging and whitespace validation. The MySQL version warning is resolved.
- `scripts/bin/harness.exe doctor` passed; Testcontainers cleaned up its containers.

BigQuery/GCS execution, Dataform, leases, worker crash recovery, search, UI,
exports, forecast, bidding, benchmarks and CI are unimplemented/unverified.
No credentials were supplied for cloud resources, and none were created.

## Result of the initial slice

The foundation is implemented and verified locally. Next implement a fenced
ingestion worker and GCS/BigQuery adapters with deterministic remote job IDs and
crash reconciliation, then Dataform/ES following spec section 21. Keep this plan
active until the MVP's remaining behavior has evidence.
