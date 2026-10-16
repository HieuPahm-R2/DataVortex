package io.datavortex.adinsight.analytics.domain.model;

import java.math.BigDecimal;

public record Metrics(
        BigDecimal impressions,
        BigDecimal clicks,
        BigDecimal conversions,
        BigDecimal cost,
        BigDecimal conversionValue,
        BigDecimal ctr,
        BigDecimal cpc,
        BigDecimal cpa,
        BigDecimal roas,
        BigDecimal cvr) {}
