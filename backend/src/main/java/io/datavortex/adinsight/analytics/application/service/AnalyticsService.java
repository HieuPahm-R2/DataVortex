package io.datavortex.adinsight.analytics.application.service;

import io.datavortex.adinsight.analytics.application.port.*;
import io.datavortex.adinsight.analytics.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;

public final class AnalyticsService {
    private final IAnalyticsScope scope;
    private final IAnalyticsWarehouse warehouse;

    public AnalyticsService(IAnalyticsScope scope, IAnalyticsWarehouse warehouse) {
        this.scope = scope;
        this.warehouse = warehouse;
    }

    public AnalyticsResult query(TenantScope tenant, AnalyticsQuery query) {
        return warehouse.query(tenant.tenantId(), query, scope.resolve(tenant.tenantId(), query));
    }
}
