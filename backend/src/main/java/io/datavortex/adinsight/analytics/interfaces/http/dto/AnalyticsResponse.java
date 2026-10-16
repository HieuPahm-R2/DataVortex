package io.datavortex.adinsight.analytics.interfaces.http.dto;

import io.datavortex.adinsight.analytics.domain.model.InputWatermark;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AnalyticsResponse(
        String source,
        LocalDate dateFrom,
        LocalDate dateTo,
        Instant dataAsOf,
        List<InputWatermark> inputWatermark,
        List<CurrencyMetricsResponse> groups,
        String requestId) {}
