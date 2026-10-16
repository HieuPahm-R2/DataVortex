package io.datavortex.adinsight.budgeting.interfaces.http.dto;

public record KpiTargetResponse(
        String metric, String operator, String targetValue, long minSamples) {}
