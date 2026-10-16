package io.datavortex.adinsight.budgeting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

public record BudgetPlan(
        UUID id,
        UUID tenantId,
        UUID campaignId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal amount,
        Currency currency,
        long revision,
        boolean active,
        List<KpiTarget> kpiTargets,
        Instant updatedAt) {
    public BudgetPlan {
        kpiTargets = List.copyOf(kpiTargets);
    }
}
