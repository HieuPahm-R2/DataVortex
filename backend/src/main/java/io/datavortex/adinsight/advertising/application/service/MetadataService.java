package io.datavortex.adinsight.advertising.application.service;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import io.datavortex.adinsight.advertising.application.port.IMetadataCatalog;
import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.util.UUID;

public final class MetadataService {
    private final IMetadataCatalog catalog;

    public MetadataService(IMetadataCatalog catalog) {
        this.catalog = catalog;
    }

    public Campaign campaign(TenantScope scope, UUID id) {
        return catalog.campaign(scope.tenantId(), id)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "Campaign not found"));
    }

    public Keyword keyword(TenantScope scope, UUID id) {
        return catalog.keyword(scope.tenantId(), id)
                .orElseThrow(() -> new BusinessException(NOT_FOUND, "Keyword not found"));
    }

    public Campaign patch(TenantScope scope, UUID id, LocalSettingsPatch patch, UUID requestId) {
        requireAdmin(scope);
        return catalog.patch(scope, id, patch, requestId);
    }

    public AdAccount createAccount(TenantScope scope, NewAccount account, UUID requestId) {
        requireAdmin(scope);
        return catalog.createAccount(scope, account, requestId);
    }

    private static void requireAdmin(TenantScope scope) {
        if (scope.role() != TenantRole.TENANT_ADMIN)
            throw new BusinessException(FORBIDDEN, "Tenant admin required to manage metadata");
    }
}
