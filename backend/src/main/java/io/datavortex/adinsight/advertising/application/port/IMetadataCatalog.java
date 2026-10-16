package io.datavortex.adinsight.advertising.application.port;

import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import java.util.Optional;
import java.util.UUID;

public interface IMetadataCatalog {
    Optional<Campaign> campaign(UUID tenantId, UUID campaignId);

    Optional<Keyword> keyword(UUID tenantId, UUID keywordId);

    Campaign patch(TenantScope scope, UUID campaignId, LocalSettingsPatch patch, UUID requestId);

    AdAccount createAccount(TenantScope scope, NewAccount account, UUID requestId);
}
