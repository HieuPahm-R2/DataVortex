package io.datavortex.adinsight.budgeting.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

public record BudgetChange(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal amount,
        Currency currency,
        long expectedRevision,
        boolean active,
        List<KpiTarget> kpiTargets) {
    public BudgetChange {
        if (periodStart == null
                || periodEnd == null
                || periodStart.isAfter(periodEnd)
                || !KpiTarget.validDecimal(amount)
                || currency == null
                || expectedRevision < 0
                || kpiTargets == null
                || kpiTargets.stream().anyMatch(java.util.Objects::isNull))
            throw new InvalidInputException(
                    "Valid period, positive DECIMAL(20,6) budget, currency, revision and KPI targets required");
        kpiTargets = List.copyOf(kpiTargets);
    }
}
