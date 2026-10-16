package io.datavortex.adinsight.budgeting.application.service;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import io.datavortex.adinsight.budgeting.application.port.IBudgetPlans;
import io.datavortex.adinsight.budgeting.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.*;
import io.datavortex.adinsight.shared.domain.*;
import java.time.LocalDate;
import java.util.UUID;

public final class BudgetService {
    private final IBudgetPlans plans;

    public BudgetService(IBudgetPlans plans) {
        this.plans = plans;
    }

    public BudgetPlan save(
            TenantScope scope, UUID campaignId, BudgetChange change, UUID requestId) {
        if (scope.role() != TenantRole.TENANT_ADMIN)
            throw new BusinessException(FORBIDDEN, "Tenant admin required to manage budgets");
        return plans.save(scope, campaignId, change, requestId);
    }

    public BudgetPlan get(TenantScope scope, UUID campaignId, LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end))
            throw new InvalidInputException("Valid budget period required");
        return plans.find(scope.tenantId(), campaignId, start, end)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "Budget plan not found"));
    }
}
