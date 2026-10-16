package io.datavortex.adinsight.advertising.application.service;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AccountPage;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.PageQuery;

public final class AccountQueryService {
    private final IAccountCatalog catalog;

    public AccountQueryService(IAccountCatalog catalog) {
        this.catalog = catalog;
    }

    public AccountPage list(TenantScope scope, PageQuery page) {
        return catalog.list(scope.tenantId(), page);
    }
}
