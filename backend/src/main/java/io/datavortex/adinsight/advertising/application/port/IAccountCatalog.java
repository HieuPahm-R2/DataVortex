package io.datavortex.adinsight.advertising.application.port;

import io.datavortex.adinsight.advertising.domain.model.AccountPage;
import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import io.datavortex.adinsight.shared.domain.PageQuery;
import java.util.Optional;
import java.util.UUID;

public interface IAccountCatalog {
    AccountPage list(UUID tenantId, PageQuery page);

    Optional<AdAccount> find(UUID tenantId, UUID accountId);
}
