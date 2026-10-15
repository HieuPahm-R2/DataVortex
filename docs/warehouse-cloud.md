# Warehouse worker: GCS through checkpoint

Authority: spec 8.2/8.3/10 and ADR 0001. Cloud execution is opt-in. Local validation
exercises MySQL coordination and cloud API boundaries; successful compilation or
mocked SDK tests do not prove execution on Google Cloud. Live proof is separate.

## Prepare a dev environment

The owner selected project `project-5699c496-36fd-4827-92b`, dataset `adtech` and
Singapore `asia-southeast1` on 2026-10-15. The bucket name and ADC setup are still
provided by the owner. Billing, bucket/dataset creation and Google sign-in happen
in the owner's account; there are no credentials in this repository.

1. Verify active billing for the selected project in Google Cloud Console.
2. Create a private Standard GCS bucket in Region / asia-southeast1. Keep Uniform
   bucket-level access and public access prevention enabled. Record its unique name.
3. Create BigQuery dataset `adtech`, also in asia-southeast1.
4. Install the Windows Google Cloud CLI from Google's installer. In a new terminal:

```powershell
rtk proxy gcloud.cmd config set project project-5699c496-36fd-4827-92b
rtk proxy gcloud.cmd auth application-default login
rtk proxy gcloud.cmd auth application-default set-quota-project project-5699c496-36fd-4827-92b
```

Sign in through Google's browser page. Do not copy ADC files or access tokens into
the repository or chat. Ordinary `gcloud auth login` alone is not local ADC setup.
Ensure the BigQuery, Storage and Dataform APIs are enabled in the project.

The local setup wizard prepared for this session is `.local/cloud-setup.sh`.
It writes only public settings to `.local/cloud.properties`; it never submits
warehouse jobs or changes IAM. It can be run from the repository root with:

```powershell
rtk proxy "C:/Program Files/Git/bin/bash.exe" .local/cloud-setup.sh
```

New clones can follow the steps above directly. The wizard is a session artifact,
not a prerequisite for the application. Official guidance:
[bucket creation](https://docs.cloud.google.com/storage/docs/creating-buckets),
[datasets](https://docs.cloud.google.com/bigquery/docs/datasets),
[ADC](https://docs.cloud.google.com/docs/authentication/provide-credentials-adc).

## Tables and Dataform source

The worker requires pre-existing tables and never creates a dataset or changes
its schema automatically. Render the accepted DDL for review:

```powershell
rtk proxy python -m scripts.render_warehouse_schema --project project-5699c496-36fd-4827-92b --dataset adtech --output .local/warehouse-schema-02.sql
```

Open that file in the BigQuery SQL editor for the dev project, verify the dataset
and location, and execute it there. It creates staging, manifests, campaign fact,
campaign/advertiser marts and publication evidence tables with partition filters.
The rendered `.local/warehouse-schema-01.sql` is already prepared in this session.

`warehouse/dataform/` is a standalone Dataform repository root. Package a source-only
archive without node_modules or credentials:

```powershell
rtk proxy python -m scripts.package_dataform --output .local/dataform-source-02.zip
```

`.local/dataform-source-01.zip` is already prepared. Put its CONTENTS at the root
of a dedicated Dataform repository/workspace, then commit them. Do not point a
Dataform repository at the root of this Java monorepo: its settings and definitions
live in the exported subtree. Follow Google's
[repository setup](https://docs.cloud.google.com/dataform/docs/create-repository)
and [repository management](https://docs.cloud.google.com/dataform/docs/manage-repository).
Record the full resource name (`projects/.../locations/.../repositories/...`) and
the immutable 40-character commit SHA containing the reviewed source.

Dataform owns two operations: staging_quality and publish_campaign. The latter
depends on the former. The worker compiles the pinned revision with explicit
project/dataset/run/tenant/account/date variables, checks compilation errors and
resolved revision, and retrieves every page of compiled actions. It persists the
compiled SQL and target binding before remote mutation. It executes those compiled
operations as named BigQuery jobs, with typed parameters, rather than issuing a
new untracked Dataform workflow invocation after an ambiguous response. This
preserves Dataform SQL ownership and deterministic warehouse reconciliation.

The existing `warehouse/bigquery/publish_campaign.sql` is the SQL owner. Regenerate
the Dataform include after changing it, then validate:

```powershell
rtk proxy python -m scripts.sync_dataform
rtk proxy .venv/Scripts/python.exe -m scripts.validate_dataform
```

Compilation is local and requires no Google credentials. The generated-source
check has positive and stale-file negative proof in the native Python suite.
Dataform project/CLI are pinned to 3.1.0 in separate npm lockfiles. The CLI stays
outside the exported cloud project. See
[Dataform CLI setup](https://docs.cloud.google.com/dataform/docs/use-dataform-cli).

## Configure and run

Public settings can be saved as `.local/cloud.properties`:

```properties
WAREHOUSE_PROJECT=project-5699c496-36fd-4827-92b
WAREHOUSE_BUCKET=your-created-bucket
WAREHOUSE_DATASET=adtech
WAREHOUSE_LOCATION=asia-southeast1
WAREHOUSE_ARTIFACTS_ROOT=D:/data-vortex/.local/worker-01
WAREHOUSE_DATAFORM_REPOSITORY=projects/your-project/locations/asia-southeast1/repositories/your-repository
WAREHOUSE_DATAFORM_REVISION=your-40-character-commit-sha
WAREHOUSE_MAXIMUM_BYTES_BILLED=268435456
```

Choose an explicit query byte cap for the dev workload; the value above is a
256 MiB example, not a provisioned quota. The adapter refuses a missing/non-positive
cap. ADC supplies authentication; this file contains no secrets. Required runtime
permissions include GCS bucket metadata/get/create objects, BigQuery dataset/table
metadata, job creation and writes to the dev tables, plus Dataform compilation
creation/query. Grant them on the intended dev resources through the owner's IAM
administration; no IAM changes are made by the worker. Production service-account
and deployment provisioning remain separate from this local dev runbook.

Run the API/local source worker as documented in local-ingestion.md. Its output
directory must be inside WAREHOUSE_ARTIFACTS_ROOT. Start a separate JVM for cloud
polling using the same DB settings:

```powershell
rtk proxy java -jar backend/target/adinsight-0.1.0-SNAPSHOT.jar `
  --server.address=127.0.0.1 --server.port=0 `
  --spring.config.additional-location=optional:file:.local/cloud.properties `
  --ingestion.cloud.enabled=true
```

The cloud worker is disabled by default. Missing resource/revision/root settings
fail startup when enabled. Optional settings are `ingestion.cloud.poll-delay`
(1000 ms) and `ingestion.cloud.lease-duration` (60s).

## Safety and recovery protocol

GCS upload rechecks local raw/normalized scope, page counts and checksums,
Parquet SHA-256, account metadata and configured location. It uploads one file
at a time using doesNotExist plus CRC32C verification. A retry accepts an existing
object only if SHA-256 metadata, CRC32C and size match; conflicts stop the run.
Complete manifests are uploaded after the data files. Local artifact pointers
remain intact; GCS pointers live in the persisted warehouse plan.

V4 creates tenant-bound warehouse execution state and one global publisher slot.
Claims use database time and row locking. A restrictive FK prevents deletion of an
occupied job from silently freeing its slot. A free slot selects a LOAD job; an
occupied slot always resumes the same job, even after its lease expires. It never
selects a newer run merely because jobs.get returned 404. Recovery can submit the
same persisted command ID, so a delayed original submit and the recovery submit
cannot become two warehouse jobs. Only a confirmed terminal failed outcome permits
a new stage attempt. Five retries are shared with the ingestion job's retry budget.

The plan persists project/dataset/location, query cap, compiled SQL, run and scope;
changing worker configuration cannot redirect an already prepared run. Each command
is committed before submit. Its ID is derived from raw run / step / stage attempt,
and a request fingerprint label rejects remote ID collisions. All IDs are retained
in ingestion_jobs.bq_job_ids. A response timeout, lookup failure or unknown status
keeps the occupied slot and command for reconciliation. It does not mark failure or
start a fresh attempt. Permission/configuration failures after a command is persisted
also retain the slot until the remote state can be inspected; restore access/config
and resume polling. Never manually clear a slot while its remote outcome is unknown.

Steps are LOAD (strict Parquet append), MANIFEST (immutable run registration),
TRANSFORM (compiled staging gate), PUBLISH (compiled transaction), CHECKPOINT.
The public job stage stays LOAD during manifest registration. A dedicated heartbeat
renews ownership during blocking SDK calls; every mutation checks token/generation
and unexpired ownership. Crash after publish resumes its terminal remote result;
crash during MySQL completion rolls back checkpoint, watermark and status together.
Only a successful publication followed by that transaction yields SUCCEEDED.
`last_success_date` records the completed run's date_to; it is not a claim that
all historical dates have been ingested.

Staging errors stop before publication. The publish transaction deduplicates equal
rows, rejects conflicting grain/stale sequences, replaces the complete scope and
checks mart totals before commit. Empty complete runs replace their scope with
zero rows. Local SQLite proof remains an oracle, not proof of BigQuery execution.

## Validation and live proof

The normal `scripts/validate.ps1 -Integration` command runs local Python/Dataform
checks, architecture/SDK boundary tests and real MySQL recovery tests. It makes no
Google Cloud calls. It covers immutable object conflicts/replay, request collisions,
global exclusive claims, ambiguous submission, running jobs, bounded terminal retries,
quality failure, and rollback/recovery of checkpoint completion.

After resources, tables, source revision and ADC are configured, explicitly run:

```powershell
rtk proxy ./mvnw.cmd -pl backend -B -ntp "-Dit.test=WarehouseCloudProof" verify
```

The live proof requires `.local/cloud.properties` and fails on missing configuration;
it is not silently skipped. It uses isolated Testcontainers MySQL and random demo
tenant/account IDs, then real REST fetch/raw/Parquet, GCS, Dataform compilation,
BigQuery load/quality/publication and MySQL completion. It checks load replay,
late conversion correction with unchanged cost, and an empty complete snapshot.
It retains dev GCS/staging/manifest evidence and never deletes buckets/datasets.
No live cloud result is claimed until that command succeeds.

Client versions are selected by Google's pinned libraries BOM 26.90.0 (Storage
2.75.0, BigQuery 2.73.0). Reference semantics:
[BOM](https://docs.cloud.google.com/java/docs/bom),
[create-only upload](https://docs.cloud.google.com/storage/docs/samples/storage-upload-file),
[BigQuery jobs](https://docs.cloud.google.com/bigquery/docs/running-jobs),
[compiled Dataform actions](https://docs.cloud.google.com/dataform/reference/rest/v1/projects.locations.repositories.compilationResults/query).
These local checks do not establish CI execution or merge protection.
