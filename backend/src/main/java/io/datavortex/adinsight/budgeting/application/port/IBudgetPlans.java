package io.datavortex.adinsight.budgeting.application.port;

import io.datavortex.adinsight.budgeting.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface IBudgetPlans {
    Optional<BudgetPlan> find(UUID tenantId, UUID campaignId, LocalDate start, LocalDate end);

    BudgetPlan save(TenantScope scope, UUID campaignId, BudgetChange change, UUID requestId);
}
