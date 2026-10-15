-- Named query parameters: tenant_id, platform, account_id, run_id, run_sequence,
-- date_from (DATE), date_to (DATE), currency, account_timezone.
-- Preconditions: verified raw/Parquet and staging assertions; ONE fenced publisher.
-- The worker must retain its MySQL lease until this job is reconciled/terminal.
-- Only then may it update publication_watermarks and ingestion_checkpoints.
ASSERT @date_from <= @date_to AS 'INVALID_DATE_SCOPE';
ASSERT (SELECT COUNT(*) = 1 FROM `YOUR_PROJECT.adtech.ingestion_manifests` WHERE run_id = @run_id)
  AS 'IMMUTABLE_MANIFEST_REQUIRED';
ASSERT (
  SELECT COUNT(*) = 1
  FROM `YOUR_PROJECT.adtech.ingestion_manifests`
  WHERE run_id = @run_id AND tenant_id = @tenant_id AND platform = @platform
    AND account_id = @account_id AND report_kind = 'CAMPAIGN'
    AND date_from = @date_from AND date_to = @date_to AND currency = @currency
    AND account_timezone = @account_timezone AND run_sequence = @run_sequence
    AND complete = TRUE AND rejected_count = 0 AND schema_version = 1
) AS 'MISSING_OR_INVALID_COMPLETE_MANIFEST';

-- Keep every staging read partition bounded; loaded row count must match the manifest.
ASSERT (
  SELECT COUNT(*) = (
    SELECT row_count FROM `YOUR_PROJECT.adtech.ingestion_manifests` WHERE run_id = @run_id
  ) FROM `YOUR_PROJECT.adtech.stg_campaign_daily`
  WHERE report_date BETWEEN @date_from AND @date_to AND run_id = @run_id
) AS 'STAGING_ROW_COUNT_MISMATCH';
ASSERT (
  SELECT COUNTIF(tenant_id != @tenant_id OR platform != @platform OR account_id != @account_id
    OR currency != @currency OR account_timezone != @account_timezone
    OR is_partial OR schema_version != 1 OR run_sequence != @run_sequence
    OR impressions < clicks OR clicks < 0 OR conversions < 0 OR cost < 0 OR conversion_value < 0) = 0
  FROM `YOUR_PROJECT.adtech.stg_campaign_daily`
  WHERE report_date BETWEEN @date_from AND @date_to AND run_id = @run_id
) AS 'STAGING_QUALITY_REJECTED';

CREATE TEMP TABLE validated_snapshot AS
SELECT DISTINCT tenant_id, platform, account_id, campaign_id, source_campaign_id,
  report_date, account_timezone, currency, impressions, clicks, conversions, cost,
  conversion_value, run_id, fetched_at
FROM `YOUR_PROJECT.adtech.stg_campaign_daily`
WHERE report_date BETWEEN @date_from AND @date_to AND run_id = @run_id;
ASSERT (
  SELECT COUNT(*) = 0 FROM (
    SELECT campaign_id, report_date, currency FROM validated_snapshot
    GROUP BY campaign_id, report_date, currency HAVING COUNT(*) > 1
  )
) AS 'CONFLICTING_DUPLICATE_GRAIN';

BEGIN TRANSACTION;
ASSERT (
  SELECT COUNT(*) = 0 FROM `YOUR_PROJECT.adtech.account_publications`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
    AND report_kind = 'CAMPAIGN' AND run_sequence >= @run_sequence AND published_run_id != @run_id
) AS 'STALE_RUN';

IF NOT EXISTS (
  SELECT 1 FROM `YOUR_PROJECT.adtech.account_publications`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
    AND report_kind = 'CAMPAIGN' AND published_run_id = @run_id
) THEN
  DELETE FROM `YOUR_PROJECT.adtech.fact_campaign_daily`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
    AND report_date BETWEEN @date_from AND @date_to;
  INSERT INTO `YOUR_PROJECT.adtech.fact_campaign_daily`
    (tenant_id, platform, account_id, campaign_id, source_campaign_id, report_date,
     account_timezone, currency, impressions, clicks, conversions, cost, conversion_value,
     last_run_id, source_fetched_at)
  SELECT tenant_id, platform, account_id, campaign_id, source_campaign_id, report_date,
    account_timezone, currency, impressions, clicks, conversions, cost, conversion_value,
    run_id, fetched_at FROM validated_snapshot;

  DELETE FROM `YOUR_PROJECT.adtech.mart_campaign_daily`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
    AND report_date BETWEEN @date_from AND @date_to;
  INSERT INTO `YOUR_PROJECT.adtech.mart_campaign_daily`
    (tenant_id, platform, account_id, campaign_id, report_date, currency, account_timezone,
     impressions, clicks, conversions, cost, conversion_value, data_as_of, published_run_id)
  SELECT tenant_id, platform, account_id, campaign_id, report_date, currency, account_timezone,
    impressions, clicks, conversions, cost, conversion_value, fetched_at, run_id
  FROM validated_snapshot;

  DELETE FROM `YOUR_PROJECT.adtech.mart_advertiser_daily`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
    AND report_date BETWEEN @date_from AND @date_to;
  INSERT INTO `YOUR_PROJECT.adtech.mart_advertiser_daily`
    (tenant_id, platform, account_id, report_date, currency, impressions, clicks, conversions,
     cost, conversion_value, data_as_of, published_run_id)
  SELECT tenant_id, platform, account_id, report_date, currency,
    SUM(impressions), SUM(clicks), SUM(conversions), SUM(cost), SUM(conversion_value),
    MAX(fetched_at), @run_id
  FROM validated_snapshot GROUP BY tenant_id, platform, account_id, report_date, currency;

  ASSERT (
    SELECT COALESCE(SUM(cost), 0) = (SELECT COALESCE(SUM(cost), 0) FROM validated_snapshot)
      AND COALESCE(SUM(clicks), 0) = (SELECT COALESCE(SUM(clicks), 0) FROM validated_snapshot)
      AND COALESCE(SUM(conversions), 0) = (SELECT COALESCE(SUM(conversions), 0) FROM validated_snapshot)
    FROM `YOUR_PROJECT.adtech.mart_advertiser_daily`
    WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id
      AND report_date BETWEEN @date_from AND @date_to
  ) AS 'MART_TOTALS_MISMATCH';
  DELETE FROM `YOUR_PROJECT.adtech.account_publications`
  WHERE tenant_id = @tenant_id AND platform = @platform AND account_id = @account_id AND report_kind = 'CAMPAIGN';
  INSERT INTO `YOUR_PROJECT.adtech.account_publications`
  SELECT tenant_id, platform, account_id, report_kind, run_sequence, run_id, fetched_at
  FROM `YOUR_PROJECT.adtech.ingestion_manifests` WHERE run_id = @run_id;
END IF;
COMMIT TRANSACTION;
