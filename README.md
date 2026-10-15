# DataVortex / AdInsight

Implementation of [spec-v1.md](spec-v1.md), starting with its first three-day
foundation slice. The Java 21 application lives in `backend/`, organized by DDD
context and inward dependencies: domain, application, infrastructure and HTTP
adapters. Tenant-scoped account reads, ingestion job submission/status, durable
fetch leases, MySQL migrations, an opt-in local REST/SOAP ingestion worker,
immutable raw artifacts and Parquet normalization are implemented.
See [architecture](docs/architecture.md) for ownership and enforced rules.

Opt-in GCS/BigQuery adapters, Dataform operations and a durable publisher/reconciliation
worker are implemented and tested locally. Live cloud verification awaits dev
resources and ADC; see the [cloud runbook](docs/warehouse-cloud.md).
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

MySQL integration uses Testcontainers with `mysql:8.4.8`, runs Flyway and
Hibernate validation, and checks tenant constraints, HTTP/CSRF, idempotency,
concurrent claims, lease fencing, API-to-Parquet ingestion, warehouse coordination
and checkpoint recovery. Cloud SDK boundary tests use controlled responses;
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
SQLite is a local test oracle; the API does not use it as an analytics backend.
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
local passwords, then start the owned database:

```powershell
rtk proxy docker compose up -d mysql
# Set DB_PASSWORD in this shell using your local secret mechanism.
rtk proxy ./mvnw.cmd -pl backend spring-boot:run
```

`.env` configures Compose; Spring does not automatically load it. Flyway creates
tables; Hibernate only validates them. Override `DB_URL`/`DB_USER` as needed.
No default credentials or tenants are installed.

Provision an ACTIVE `users` row with a BCrypt password hash, an ACTIVE tenant,
and a `memberships` row through your local database administration tool. Account
metadata must belong to that tenant. The initial API supports:

```http
GET /api/v1/accounts
Authorization: Basic <base64(email:password)>
X-Tenant-Id: <tenant UUID>
```

Basic authentication is the initial access mechanism in this empty repository;
serve it over HTTPS outside loopback. Requests verify active tenant membership
and return credential-free account metadata. Missing/invalid tenant: 400;
missing credentials: 401; no membership: 403; database dependency failure: 503.
Responses include a generated `requestId` and `X-Request-Id` header. Actuator
endpoints require authentication. Platform-admin operations are not implemented.

### Ingestion job API

1. Authenticate and call `GET /api/v1/csrf` with `X-Tenant-Id`. Retain the
   returned session cookie, token and header name. Each new session needs its token.
2. As TENANT_ADMIN, submit a request with that cookie/token and an
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

Observed on 2026-10-15: 66 tests pass (19 Python, 25 backend unit/architecture/SDK,
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
