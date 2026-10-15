package io.datavortex.adinsight.advertising.application.port;

import io.datavortex.adinsight.advertising.domain.model.AdAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountCatalog {
    List<AdAccount> list(UUID tenantId);

    Optional<AdAccount> find(UUID tenantId, UUID accountId);
}
