package io.datavortex.adinsight.budgeting.interfaces.http.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BudgetPlanResponse(
        UUID id,
        UUID campaignId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String amount,
        String currency,
        long revision,
        boolean active,
        List<KpiTargetResponse> kpiTargets,
        Instant updatedAt,
        String requestId) {}
