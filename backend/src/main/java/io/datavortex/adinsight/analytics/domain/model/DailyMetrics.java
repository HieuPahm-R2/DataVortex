package io.datavortex.adinsight.analytics.domain.model;

import java.time.LocalDate;

public record DailyMetrics(LocalDate date, Metrics metrics) {}
