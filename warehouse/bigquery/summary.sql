-- Ratios are computed from sums. Never average row-level CTR/CPC/CPA.
SELECT currency, SUM(impressions) AS impressions, SUM(clicks) AS clicks,
  SUM(conversions) AS conversions, SUM(cost) AS cost,
  SUM(conversion_value) AS conversion_value,
  SAFE_DIVIDE(SUM(clicks), SUM(impressions)) AS ctr,
  SAFE_DIVIDE(SUM(cost), SUM(clicks)) AS cpc,
  SAFE_DIVIDE(SUM(cost), SUM(conversions)) AS cpa,
  SAFE_DIVIDE(SUM(conversion_value), SUM(cost)) AS roas,
  SAFE_DIVIDE(SUM(conversions), SUM(clicks)) AS cvr
FROM `YOUR_PROJECT.adtech.mart_campaign_daily`
WHERE tenant_id = @tenant_id AND currency = @currency
  AND report_date BETWEEN @date_from AND @date_to
GROUP BY currency;
