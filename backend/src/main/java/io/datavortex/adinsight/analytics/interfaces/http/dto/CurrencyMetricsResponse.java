package io.datavortex.adinsight.analytics.interfaces.http.dto;

import java.util.List;
import java.util.Map;

public record CurrencyMetricsResponse(
        String currency, Map<String, String> totals, List<DailyMetricsResponse> days) {}
