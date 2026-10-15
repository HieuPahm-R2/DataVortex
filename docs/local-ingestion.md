# Local ingestion worker

Authority: spec sections 6, 8 and 10; ADR 0001; the local-worker slice in the
[active plan](plans/active/adinsight-v1.md). This slice ends at verified Parquet.
The opt-in [warehouse worker](warehouse-cloud.md) continues with GCS/BigQuery,
remote reconciliation, publisher fencing and checkpoint completion. Its live
service proof is separate from the verified local slice.

## Start and submit

Use the Python environment and MySQL/backend setup in [README](../README.md).
The API process leaves polling disabled by default. Start a separate JVM with
the same DB environment and an ephemeral loopback HTTP port for the worker.

For a fixture-backed source, first generate a fresh fixture directory:

```powershell
rtk proxy .venv/Scripts/python.exe -m pipeline generate --output .local/source-01
rtk proxy .venv/Scripts/python.exe -m pipeline.mock_source --fixtures .local/source-01 --port 8091
```

The source serves campaign rows from those fixtures. Its metadata.json is the
source registry: local MySQL tenant, ad-account and campaign UUIDs, platform,
source IDs, currency and timezone must match it. Provision those demo metadata
rows and tenant membership through the local database administration tool as in
README. The mock never changes MySQL metadata. Alternatively, implement the
mock protocol below against the metadata already present in your local database.
Use the fixture's date range when submitting a job.

After building the backend, run this from a separate terminal (adjust absolute
paths when cloning elsewhere):

```powershell
rtk proxy java -jar backend/target/adinsight-0.1.0-SNAPSHOT.jar `
  --server.address=127.0.0.1 --server.port=0 `
  --ingestion.local.enabled=true `
  --ingestion.local.root=D:/data-vortex `
  --ingestion.local.output=D:/data-vortex/.local/worker-01 `
  --ingestion.local.python=D:/data-vortex/.venv/Scripts/python.exe `
  --ingestion.local.endpoint=http://127.0.0.1:8091
```

Submit/read a CAMPAIGN ingestion job through the authenticated API/CSRF flow
in README. One worker processes one job at a time. Ctrl+C stops the foreground
worker or mock process. Worker configuration is explicit and independent of any
source credential references; this adapter only supports unauthenticated mocks.
KEYWORD and GOOGLE_ADS requests fail with UNSUPPORTED_SCHEMA in this local adapter.

Optional settings:

| Property | Default | Meaning |
| --- | --- | --- |
| ingestion.local.poll-delay | 1000 | Milliseconds between completed polls |
| ingestion.local.lease-duration | 60s | Renewed at one third of its duration |
| ingestion.local.request-timeout | 30s | Source socket timeout |
| ingestion.local.process-timeout | 30m | Maximum duration of each subprocess stage |

The worker passes account/campaign metadata from tenant/account-scoped MySQL
queries into the Python adapter. The source response cannot select that metadata
or overwrite its tenant/currency/timezone. No credentials are exported.

## Artifacts and recovery

Raw layout follows the spec:
`raw/platform=.../tenant=.../account=.../report=CAMPAIGN/run=<new UUID>/`.
Each run has started.json, metadata.json, per-page JSONL gzip and immutable page
receipts with SHA-256 over compressed bytes. A final manifest.json records scope,
UTC fetch time, sequence, job ID/attempt, counts, checksums, completeness and safe
error codes. Only the final successful page can make that manifest complete.
Each source page is bounded to 16 MiB; the worker never buffers all pages.

Normalization verifies every page's checksum and checks row tenant, account,
platform, campaign mapping, report date, timezone, currency and exact numerics.
The existing pipeline writes normalized/run=<UUID>/campaign.parquet and creates
its complete manifest only after validation. It fails on the first invalid row;
rowsRejected counts the recorded rejected row, not unexamined rows. rejected.json
contains the error, raw manifest, page, line and original row when available.
Raw remains available for replay and diagnosis. A partial Parquet file without a
complete normalized manifest is never eligible for LOAD.

HTTP timeout/connection failures, 408/429/5xx and incomplete terminal pagination
retry with at most five explicit retries. Retry-After seconds or HTTP date is
honored. Other HTTP/schema errors fail permanently. A retried fetch
always starts at the first page in a fresh UUID directory; source cursors cannot
be filesystem paths. A killed fetch leaves started.json and completed receipts,
without a final complete manifest. Account failures do not block later jobs.

MySQL lease token/generation and database time guard artifact writes and all
local-stage transitions. Expired FETCH/RAW_COMPLETE/NORMALIZE jobs are reclaimable;
reclaim resets to FETCH without consuming a retry. A worker that loses ownership
stops its owned subprocess and cannot record results or defer/fail the new owner.
An abrupt JVM kill may leave an orphan Python process writing its private run;
it has no database credentials or publication capability. The replacement worker
uses a fresh run and cannot accept the orphan's result. Subprocess duration limits
bound a hung live worker; source socket timeouts also bound abandoned fetch calls.

The job stores raw_manifest_uri, normalized_manifest_uri and rejected_uri for
local operator inspection, plus the existing HTTP quality counters. After valid
Parquet, it releases the local lease and waits at RUNNING/LOAD. rowsLoaded stays
zero, finishedAt stays null, and checkpoint/watermark tables remain untouched.
LOAD jobs are excluded from local claims; the separately enabled warehouse worker
owns them after dev cloud configuration is supplied.

## Mock protocol and proof

REST: GET /rest/campaigns with account_id, source_account_id, date_from, date_to,
page (empty initially). JSON response has rows, next_page (opaque string or null)
and complete (true on the terminal page). Rows use the schema emitted by
pipeline.fixtures; source IDs retain their leading zeroes.

SOAP: POST /soap, SOAP 1.1 Envelope/Body with GetCampaignReport and the same
fields, SOAPAction GetCampaignReport. The response Body contains
GetCampaignReportResponse/rows/row with one element per row field, next_page and
complete. The local mock contract uses unqualified operation/row elements.
DOCTYPE/entities and HTTP redirects are rejected. This is a mock adapter, not a
generic WSDL connector or a production advertising API client.

The mock CLI supports --scenario baseline, empty, partial, timeout, rate-limit
and unavailable. The existing generator provides late-correction fixtures.

```powershell
rtk proxy powershell -NoProfile -File scripts/validate.ps1 -Integration
```

This seeds isolated Testcontainers MySQL state and exercises authenticated HTTP
submission/status, REST and SOAP fetch, raw and Parquet, slow-call lease renewal,
timeout/partial retry, rejection evidence, stale ownership and local-stage crash
reclaim. Python tests additionally kill a real fetch subprocess mid-pagination
and verify immutable restart/evidence. These are local checks; CI and branch
protection remain unconfigured/unverified.
