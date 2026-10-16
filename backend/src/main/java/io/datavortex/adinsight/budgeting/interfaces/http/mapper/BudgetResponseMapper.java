package io.datavortex.adinsight.budgeting.interfaces.http.mapper;

import io.datavortex.adinsight.budgeting.domain.model.BudgetPlan;
import io.datavortex.adinsight.budgeting.interfaces.http.dto.*;

public final class BudgetResponseMapper {
    private BudgetResponseMapper() {}

    public static BudgetPlanResponse from(BudgetPlan plan, String requestId) {
        return new BudgetPlanResponse(
                plan.id(),
                plan.campaignId(),
                plan.periodStart(),
                plan.periodEnd(),
                plan.amount().toPlainString(),
                plan.currency().getCurrencyCode(),
                plan.revision(),
                plan.active(),
                plan.kpiTargets().stream()
                        .map(
                                target ->
                                        new KpiTargetResponse(
                                                target.metric().name(),
                                                target.operator().name(),
                                                target.targetValue().toPlainString(),
                                                target.minSamples()))
                        .toList(),
                plan.updatedAt(),
                requestId);
    }
}
