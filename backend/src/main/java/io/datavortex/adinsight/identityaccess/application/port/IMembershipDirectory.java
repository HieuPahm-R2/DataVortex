package io.datavortex.adinsight.identityaccess.application.port;

import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import java.util.Optional;
import java.util.UUID;

public interface IMembershipDirectory {
    Optional<TenantRole> role(UUID tenantId, String userId);
}
