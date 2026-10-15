# 0001: Campaign grain and snapshot publication

Date: 2026-10-14. Status: Accepted from spec-v1 sections 6–10.

Campaign fact grain is tenant/platform/account/campaign/report_date/currency.
Keyword performance will have a separate fact; joining those two grains before
aggregation is forbidden by the spec because it multiplies campaign totals.
Report dates follow account timezone; fetched/audit/job timestamps are UTC.

Daily source values are complete snapshots, not deltas. A complete manifest,
verified pages, correct account mapping and zero unapproved rejected rows gate
publication. Empty complete snapshots delete their scope; incomplete runs never
replace it. Identical duplicates deduplicate; conflicting duplicate grain is
rejected because no source ordering authority is available.

BigQuery replaces tenant/account/platform/date scope and publishes both campaign
and advertiser marts in a transaction. A single worker must hold the MySQL
publisher lease until the remote job is terminal/reconciled; then it updates
checkpoints. Sequence checks in SQL are additional protection, not a distributed
lease implementation. BigQuery transaction semantics support this design:
[official documentation](https://docs.cloud.google.com/bigquery/docs/transactions).

The local SQLite model in `pipeline/reference.py` is a test oracle only. It
uses exact Decimal values and proves local replacement and ordering. It does
not prove BigQuery execution, GCS loading, MySQL fencing or cross-store recovery.
