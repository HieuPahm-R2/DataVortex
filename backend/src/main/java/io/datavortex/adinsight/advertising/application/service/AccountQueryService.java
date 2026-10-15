package io.datavortex.adinsight.advertising.application.service;

import io.datavortex.adinsight.advertising.application.port.AccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import java.util.List;

public final class AccountQueryService {
    private final AccountCatalog catalog;

    public AccountQueryService(AccountCatalog catalog) {
        this.catalog = catalog;
    }

    public List<AdAccount> list(TenantScope scope) {
        return catalog.list(scope.tenantId());
    }
}
