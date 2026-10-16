package io.datavortex.adinsight.analytics.domain.model;

import java.time.Instant;
import java.util.List;

public record AnalyticsResult(
        String source,
        Instant dataAsOf,
        List<InputWatermark> inputWatermark,
        List<CurrencyMetrics> groups) {
    public AnalyticsResult {
        inputWatermark = List.copyOf(inputWatermark);
        groups = List.copyOf(groups);
    }
}
