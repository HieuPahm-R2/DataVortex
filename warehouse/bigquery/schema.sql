-- Replace YOUR_PROJECT with the project ID before provisioning.
-- Create the adtech dataset separately in the SAME location as the GCS load bucket.
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.stg_campaign_daily` (
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  campaign_id STRING NOT NULL, source_campaign_id STRING NOT NULL,
  report_date DATE NOT NULL, account_timezone STRING NOT NULL, currency STRING NOT NULL,
  impressions INT64 NOT NULL, clicks INT64 NOT NULL, conversions NUMERIC NOT NULL,
  cost NUMERIC NOT NULL, conversion_value NUMERIC NOT NULL,
  run_id STRING NOT NULL, run_sequence INT64 NOT NULL, fetched_at TIMESTAMP NOT NULL,
  is_partial BOOL NOT NULL, schema_version INT64 NOT NULL
) PARTITION BY report_date CLUSTER BY tenant_id, account_id, run_id
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.ingestion_manifests` (
  run_id STRING NOT NULL, run_sequence INT64 NOT NULL,
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  report_kind STRING NOT NULL, date_from DATE NOT NULL, date_to DATE NOT NULL,
  currency STRING NOT NULL, account_timezone STRING NOT NULL, fetched_at TIMESTAMP NOT NULL,
  complete BOOL NOT NULL, row_count INT64 NOT NULL, rejected_count INT64 NOT NULL,
  schema_version INT64 NOT NULL, raw_manifest_sha256 STRING NOT NULL
) CLUSTER BY tenant_id, account_id, run_id;

CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.fact_campaign_daily` (
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  campaign_id STRING NOT NULL, source_campaign_id STRING NOT NULL,
  report_date DATE NOT NULL, account_timezone STRING NOT NULL, currency STRING NOT NULL,
  impressions INT64 NOT NULL, clicks INT64 NOT NULL, conversions NUMERIC NOT NULL,
  cost NUMERIC NOT NULL, conversion_value NUMERIC NOT NULL,
  last_run_id STRING NOT NULL, source_fetched_at TIMESTAMP NOT NULL
) PARTITION BY report_date CLUSTER BY tenant_id, account_id, campaign_id
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.mart_campaign_daily` (
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  campaign_id STRING NOT NULL, report_date DATE NOT NULL, currency STRING NOT NULL,
  account_timezone STRING NOT NULL,
  impressions INT64 NOT NULL, clicks INT64 NOT NULL, conversions NUMERIC NOT NULL,
  cost NUMERIC NOT NULL, conversion_value NUMERIC NOT NULL,
  data_as_of TIMESTAMP NOT NULL, published_run_id STRING NOT NULL
) PARTITION BY report_date CLUSTER BY tenant_id, account_id, campaign_id
OPTIONS (require_partition_filter = TRUE);

CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.mart_advertiser_daily` (
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  report_date DATE NOT NULL, currency STRING NOT NULL,
  impressions INT64 NOT NULL, clicks INT64 NOT NULL, conversions NUMERIC NOT NULL,
  cost NUMERIC NOT NULL, conversion_value NUMERIC NOT NULL,
  data_as_of TIMESTAMP NOT NULL, published_run_id STRING NOT NULL
) PARTITION BY report_date CLUSTER BY tenant_id, account_id
OPTIONS (require_partition_filter = TRUE);

-- Cloud job completion/replay evidence, independent of the MySQL checkpoint.
CREATE TABLE IF NOT EXISTS `YOUR_PROJECT.adtech.account_publications` (
  tenant_id STRING NOT NULL, platform STRING NOT NULL, account_id STRING NOT NULL,
  report_kind STRING NOT NULL, run_sequence INT64 NOT NULL,
  published_run_id STRING NOT NULL, data_as_of TIMESTAMP NOT NULL
) CLUSTER BY tenant_id, account_id;
