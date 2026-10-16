package io.datavortex.adinsight.analytics.domain.model;

import java.util.List;

public record CurrencyMetrics(String currency, Metrics totals, List<DailyMetrics> days) {
    public CurrencyMetrics {
        days = List.copyOf(days);
    }
}
