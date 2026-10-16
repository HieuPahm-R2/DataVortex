# DataVortex / AdInsight

The Java 21 application lives in `backend/`, organized by DDD
context and inward dependencies: domain, application, infrastructure and HTTP
adapters. Tenant-scoped account reads, ingestion job submission/status, durable
fetch leases, MySQL migrations, an opt-in local REST/SOAP ingestion worker,
immutable raw artifacts and Parquet normalization are implemented.
See [architecture](docs/architecture.md) for ownership and enforced rules.

Opt-in GCS/BigQuery adapters, Dataform operations and a durable publisher/reconciliation
worker are implemented and tested locally. Live cloud verification awaits dev
resources and ADC; see the [cloud runbook](docs/warehouse-cloud.md).
Metadata create/detail/local settings, budget/KPI and opt-in local analytics are
also implemented; follow [local backend without billing](docs/local-backend.md).
Elasticsearch, UI, reports, forecast and bidding remain in the
[active plan](docs/plans/active/adinsight-v1.md). This is not a completed MVP.

## Local validation (Windows PowerShell)

Prerequisites: JDK 21, Python 3.14, Node.js and RTK (`rtk`). Maven Wrapper downloads
Maven 3.9.12; Python dependencies and Dataform npm dependencies are pinned.

```powershell
rtk proxy python -m venv .venv
rtk proxy .venv/Scripts/python.exe -m pip install -r requirements.txt
rtk proxy npm --prefix tools/dataform ci --ignore-scripts
rtk proxy npm --prefix warehouse/dataform ci --ignore-scripts
rtk proxy powershell -NoProfile -File scripts/validate.ps1
```

This runs pipeline tests, actual local Dataform compilation and backend tests, then builds
`backend/target/adinsight-0.1.0-SNAPSHOT.jar`. It includes ArchUnit dependency
rules and does not run MySQL integration.
Java formatting is checked during validation. Apply it after editing with
`rtk proxy ./mvnw.cmd -pl backend spotless:apply`.
With a working Docker-compatible daemon, run the complete integration check:

```powershell
rtk proxy powershell -NoProfile -File scripts/validate.ps1 -Integration
```

On this machine, the existing Podman VM provides the compatible daemon:
`rtk proxy podman machine start podman-machine-default`. Start it before the
integration command and stop it afterward if it was originally stopped.

Integration uses Testcontainers with `mysql:8.4.8` and
`quay.io/keycloak/keycloak:26.8.1`, runs Flyway and
Hibernate validation, and checks tenant constraints, HTTP/CSRF, idempotency,
concurrent claims, lease fencing, API-to-Parquet ingestion, warehouse coordination
and checkpoint recovery. OIDC proof uses real Keycloak discovery/signatures,
explicit identity links and live DB role/revocation checks.
Cloud SDK boundary tests use controlled responses;
native validation submits no Google Cloud jobs.
`mvnw verify` fails when a container daemon is unavailable; it does not silently skip proof.
On Linux/macOS, use `rtk proxy sh mvnw -B -ntp package` or `verify`, and
`rtk proxy .venv/bin/python -m unittest discover -s tests -v`.

## Local pipeline demo

From the repository root, choose a fresh output directory:

```powershell
rtk proxy .venv/Scripts/python.exe -m scripts.demo_pipeline --output .local/demo-01
```

The demo creates 2 tenants, 4 accounts (REST/SOAP), 20 campaigns, 200 keyword
metadata records and 1,200 campaign daily rows over 60 days. It writes raw gzip
pages with checksums, explicit `decimal128(38,9)` Parquet, then validates replay,
late corrections, partial failure and an empty complete snapshot. Output includes
`evidence.json` and a SQLite reference warehouse. All metrics are simulated.
SQLite also supports the explicitly enabled dev analytics adapter. Prepare an
API dataset and matching MySQL seed with the [local backend runbook](docs/local-backend.md).
Output paths are immutable; reusing an existing directory fails.

Individual tools:

```powershell
rtk proxy .venv/Scripts/python.exe -m pipeline generate --output data/generated
rtk proxy .venv/Scripts/python.exe -m pipeline normalize --manifest PATH_TO_MANIFEST --metadata data/generated/metadata.json
rtk proxy .venv/Scripts/python.exe -m pipeline replay-local --manifest PATH_TO_MANIFEST --metadata data/generated/metadata.json
```

`generate` prints manifest paths. Scenarios are `baseline`, `correction`, `empty`,
and `partial`. `normalize` writes a complete manifest only after every page has
passed validation; rejected runs retain diagnostic evidence and cannot publish.

## Backend setup

Docker Compose is local development only. Copy `.env.example` to `.env`, choose
local database and Keycloak admin passwords, then start the local stack:

```powershell
rtk proxy docker compose up -d
# Set DB_PASSWORD in this shell using your local secret mechanism.
rtk proxy ./mvnw.cmd -pl backend spring-boot:run
```

`.env` configures Compose; Spring does not automatically load it. Flyway creates
tables; Hibernate only validates them. Override `DB_URL`/`DB_USER` as needed.
No default credentials or tenants are installed.

Default authentication uses Keycloak/OIDC. Set `OIDC_ISSUER_URI` and
`OIDC_AUDIENCE`, then provision an explicit `(issuer, subject)` identity link to
an ACTIVE local user and tenant membership. Follow the
[Keycloak setup and backend foundation runbook](docs/backend-foundation.md),
including the shared Compose network and environment setup. Account
metadata must belong to that tenant. The account API supports:

```http
GET /api/v1/accounts?page=0&size=20
Authorization: Bearer <Keycloak access token>
X-Tenant-Id: <tenant UUID>
```

Basic/BCrypt is available only with `SPRING_PROFILES_ACTIVE=local` and a
non-NULL local password hash. Requests verify active local identity and tenant membership
and return credential-free account metadata. Missing/invalid tenant: 400;
missing credentials: 401; no membership: 403; database dependency failure: 503.
Accounts are paginated in MySQL (default 20, max 100, ascending ID), with page,
size and hasMore metadata. Errors include safe field details. Responses include a generated `requestId` and `X-Request-Id` header. Actuator
endpoints require authentication. Platform-admin operations are not implemented.

### Ingestion job API

1. Authenticate using a Keycloak access token and `X-Tenant-Id`. In the explicit
   local Basic profile only, call `GET /api/v1/csrf` with `X-Tenant-Id`. Retain the
   returned session cookie, token and header name. Each new session needs its token.
2. As TENANT_ADMIN, submit a request with the Bearer token (or local Basic plus
   CSRF cookie/token) and an
   `Idempotency-Key` header (1–100 characters):

```http
POST /api/v1/ingestion-jobs
X-Tenant-Id: <tenant UUID>
Idempotency-Key: backfill-2026-10
Content-Type: application/json

{"accountId":"<account UUID>","reportKind":"CAMPAIGN","dateFrom":"2026-10-01","dateTo":"2026-10-14"}
```

The response is 202 with a `Location` pointing to
`GET /api/v1/ingestion-jobs/{id}`. Same key/scope returns the same job; changed
scope returns 409. TENANT_ADMIN and ANALYST can read status; VIEWER cannot.
Invalid input is 400, and out-of-tenant account/job IDs are 404. Tenant values
in the body never select authorization scope. The full contract is in
[OpenAPI](docs/openapi.yaml).

Jobs remain PENDING until the local worker is enabled. Follow the
[local ingestion runbook](docs/local-ingestion.md) to run REST/SOAP mocks and a
separate worker process. The worker polls MySQL, renews its lease during source
calls and normalization, writes fresh raw runs and invokes the existing Parquet
pipeline against MySQL-owned account/campaign metadata. Successful local runs
wait at RUNNING/LOAD, with `rowsLoaded=0`. The worker cannot publish a mart,
advance a checkpoint or mark jobs SUCCEEDED. Enable the separate
[warehouse worker](docs/warehouse-cloud.md) after configuring dev cloud resources
to continue through LOAD/TRANSFORM/PUBLISH/CHECKPOINT.

Without cloud billing, use the explicit local publication command and dev
analytics profile in [local-backend.md](docs/local-backend.md). This does not
mark cloud jobs successful. That guide also covers metadata create/detail,
versioned local settings and the V6 budget/KPI API.

`rtk proxy docker compose stop mysql` stops this Compose service and preserves
the local volume. Integration tests create and clean up their own containers.

## Warehouse and evidence limits

`warehouse/bigquery/schema.sql` and `publish_campaign.sql` own the explicit schema
and transactional replacement. `warehouse/dataform/` packages quality and publish
operations. The cloud adapter verifies/upload snapshots, persists deterministic
requests before submission and reconciles existing jobs. A global MySQL publisher
slot retains its job across owner expiry and ambiguous outcomes. Only confirmed
publication followed by an atomic checkpoint/watermark transaction yields SUCCEEDED.
Resources, permissions, Dataform revision and query byte cap are explicit; cloud
polling is disabled by default. Follow the cloud runbook for setup and live proof.

Observed on 2026-10-16 after metadata/budget/dev analytics: 111 tests pass
(24 Python, 46 backend unit/HTTP/architecture/SDK, 41 real MySQL/Keycloak
integration), actual Dataform compilation, Spotless and whitespace checks pass,
and the JAR packages V6 and the dev warehouse profile. Local proof imports the
generated fixture metadata into MySQL, calls real analytics HTTP endpoints and
verifies Decimal totals, currency groups, tenant/deleted-parent isolation and 503
for a missing warehouse. Concurrent metadata/budget writes and forced audit
failures exercise optimistic conflicts and transaction rollback.

Earlier on 2026-10-16 after the backend foundation changes: 87 tests pass
(19 Python, 41 backend unit/HTTP/architecture/SDK, 27 real MySQL/Keycloak
integration), Dataform 3.1.0 compiles, formatting and whitespace checks pass,
and the JAR builds. Keycloak proof includes real authorization-code/S256 PKCE
login, API audience/ID-token rejection, local identity links and live DB roles.

Earlier evidence on 2026-10-15: 66 tests pass (19 Python, 25 backend unit/architecture/SDK,
22 MySQL/HTTP integration), Dataform 3.1.0 compiles, formatting passes and the JAR builds.
REST/SOAP end-to-end cases use the opt-in scheduler. Warehouse proof includes
lost submission responses, stale ownership, terminal retries, quality failure,
and atomic completion rollback/recovery; it does not substitute for live cloud proof.
Integration used the existing Podman VM and MySQL 8.4.8.
Detailed evidence is recorded in the active plan.
GCS/BigQuery/Dataform service execution is unverified. `WarehouseCloudProof` is an
explicit opt-in command that fails on missing configuration; it is not silently skipped.
No benchmark, cloud deployment, CI run or
merge protection is claimed.

Harness core 0.1.10 was installed after SHA-256/version verification;
`rtk proxy scripts/bin/harness.exe doctor` checks its local installation.
See the [repository map](docs/README.md) before continuing implementation.
