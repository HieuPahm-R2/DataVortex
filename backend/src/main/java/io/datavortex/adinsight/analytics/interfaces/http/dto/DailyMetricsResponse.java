package io.datavortex.adinsight.analytics.interfaces.http.dto;

import java.time.LocalDate;
import java.util.Map;

public record DailyMetricsResponse(LocalDate date, Map<String, String> metrics) {}
