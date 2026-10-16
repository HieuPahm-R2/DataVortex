# Local backend without cloud billing

The owner approved a development warehouse in [ADR 0003](decisions/0003-local-warehouse-and-budget-policy.md).
MySQL remains the metadata authority; BigQuery remains the cloud warehouse.
The dev API reads validated SQLite snapshots produced by the existing pipeline.
No Google account, ADC, bucket or billing activation is required for this path.

## Prepare a reproducible dataset

Install the README prerequisites and run migrations against an isolated dev MySQL.
From the repository root:

```powershell
rtk proxy .venv/Scripts/python.exe -m scripts.prepare_local_backend --output .local/backend-demo-01
```

The directory must be new. This creates 2 tenants, 4 accounts, 20 campaigns, 200
keywords, 1,200 daily facts, immutable raw and Parquet files, `warehouse.sqlite`,
a reviewable `metadata.sql`, and `setup.json` with tenant/campaign/date values.
It creates files only. All performance metrics are simulated.

Import `metadata.sql` into your migrated, empty dev database using your MySQL
client. Inserts intentionally fail on existing UUIDs; do not treat this as an
upsert/import API. The script contains no users, passwords or memberships.
Provision an existing Keycloak user's explicit identity link and membership
using [backend-foundation.md](backend-foundation.md), selecting a tenant from
`setup.json`. The integration suite executes this exact SQL against isolated
MySQL, then calls the real HTTP API against the generated warehouse.

## Start the API

Set the existing DB/OIDC variables, then select the warehouse profile separately
from authentication:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev-warehouse'
$env:LOCAL_WAREHOUSE_ROOT = 'D:/data-vortex'
$env:LOCAL_WAREHOUSE_DATABASE = 'D:/data-vortex/.local/backend-demo-01/warehouse.sqlite'
$env:LOCAL_WAREHOUSE_PYTHON = 'D:/data-vortex/.venv/Scripts/python.exe'
rtk proxy ./mvnw.cmd -pl backend spring-boot:run
```

`dev-warehouse` retains Keycloak authentication. Explicit
`local,dev-warehouse` selects the existing Basic/BCrypt + CSRF development mode.
Do not use the `local` authentication profile for production. Local analytics
cannot start without the `dev-warehouse` profile. Its process timeout defaults
to 30 seconds and can be set through `LOCAL_WAREHOUSE_TIMEOUT`.

Use your Bearer token and `X-Tenant-Id`:

```http
GET /api/v1/analytics/summary?dateFrom=2026-08-15&dateTo=2026-10-13
GET /api/v1/analytics/summary?dateFrom=2026-08-15&dateTo=2026-10-13&currency=VND
GET /api/v1/analytics/campaigns/{campaignId}/daily?dateFrom=2026-08-15&dateTo=2026-10-13
```

Summary supports an optional `accountId`. Date ranges are inclusive, ordered,
and at most 366 days. Without currency, results contain separate currency groups;
there is no conversion or addition across currencies. Decimal metrics are JSON
strings, zero-denominator ratios are null, and ratios use sums first. Daily
results contain observed dates only: absent source dates are not fabricated as
zero. An empty valid query has zero totals/null ratios. A missing, locked,
corrupt or inaccessible warehouse returns safe `WAREHOUSE_UNAVAILABLE` 503.

Each response identifies `source=LOCAL`, source `dataAsOf` and per-account
`inputWatermark` (sequence/run/time). The aggregate dataAsOf is the earliest
available selected account publication; absent publications are visible as
missing watermark entries. It is a source timestamp, not a claim that every
date/account has complete coverage. Facts and watermarks come from one SQLite
read transaction. Active campaign IDs and account currency come from tenant-scoped
MySQL; deleted campaigns are excluded, and detail IDs outside the tenant return 404.
Metadata authorization and warehouse reads are separate transactions: this does
not promise a distributed snapshot across MySQL and SQLite.

The reader opens SQLite with [URI mode=ro](https://www.sqlite.org/uri.html).
It never creates a missing warehouse or changes MySQL cloud checkpoints.
The SQLite database is intended for local development, not production analytics
load or multi-host publication.

## Publish a local ingestion artifact

The existing REST/SOAP ingestion worker still produces raw and Parquet artifacts
and parks its MySQL job at RUNNING/LOAD. Local publication is an explicit command:

```powershell
rtk proxy .venv/Scripts/python.exe -m pipeline replay-local --manifest RAW_MANIFEST --metadata METADATA_JSON --warehouse .local/backend-demo-01/warehouse.sqlite
```

For worker runs, `METADATA_JSON` is `metadata.json` alongside that raw manifest.
Publication revalidates the complete raw snapshot and checksums, replaces only
its account/date scope, preserves exact decimal values, rejects older sequence
numbers, and replays the same immutable run without double counting. Independent
publishers serialize on a SQLite write transaction. Use the same warehouse file
configured for the API. Partial/rejected runs cannot publish.

This command does not mark the cloud-oriented ingestion job SUCCEEDED, create
BigQuery job IDs, or advance MySQL cloud publication/checkpoint tables.
Automatic local job completion, local report exports and forecast jobs are
subsequent work. The existing opt-in cloud publisher is preserved; live GCS,
BigQuery and Dataform execution remains unverified. Cloud-mode analytics
currently returns 503 until its read adapter is implemented; it never silently
falls back to local data.

## Metadata and budgets

The new HTTP contract is also documented in [OpenAPI](openapi.yaml).

- `POST /accounts`: TENANT_ADMIN creates metadata, returning 201 with the account
  and requestId. Duplicate platform/source ID within the tenant returns 409.
  Optional credentialRef accepts a Secret Manager resource reference; its content
  and provider connectivity are not verified here. Responses/audits exclude it.
- `GET /campaigns/{id}`, `GET /keywords/{id}`: members can read scoped details;
  deleted campaign/ad-group parents hide their keywords. Keyword bidMicros is a
  decimal integer string.
- `PATCH /campaigns/{id}/local-settings`: TENANT_ADMIN sends expectedVersion and
  at least one of localNote/localTags. Omitted fields are preserved; null clears
  note/tags. Name/status remain source-owned. An unchanged patch retains version
  and creates no event. A stale version returns 409, including stale no-op requests.
  A changed patch increments searchVersion and commits an immutable full campaign
  projection plus audit in the same transaction. The ES indexer remains pending.
- `PUT /campaigns/{id}/budget-plan`: TENANT_ADMIN sends exact periodStart/end,
  amount, currency, expectedRevision, active and kpiTargets. The period pair
  identifies the plan; revision 0 creates it and a current positive revision
  updates it. GET on the same path with periodStart/end lets members refresh it.
  Currency must match the account; active periods cannot overlap, including
  endpoints. A next-day adjacent period is allowed. Deactivate a plan before
  replacing it with a different overlapping period.
- KPI metrics are SPEND/CONVERSIONS/CPA/ROAS and operators LTE/GTE. Positive
  budget/targets use DECIMAL(20,6) without rounding; minSamples is nonnegative.
  Budget revision, replacement KPI targets and audit commit/rollback together.
  Budget/KPI changes do not alter the search projection because it excludes them.

Metadata SQL transactions and optimistic conflicts are exercised with real MySQL
HTTP tests, simultaneous writers and forced audit failures. Existing Keycloak,
tenant, CSRF, ingestion and warehouse recovery checks remain part of the native
`scripts/validate.ps1 -Integration` command. No CI/hook/branch-protection change
is included.

Observed on 2026-10-16: this native command passed 111 tests (24 Python,
46 backend unit/HTTP/architecture/SDK and 41 real MySQL/Keycloak integration),
with no failures/errors/skips. Dataform compiled, Java formatting/whitespace checks
passed and the packaged JAR includes V6 and the dev warehouse profile. This is
local evidence; no Google Cloud service execution was attempted.

