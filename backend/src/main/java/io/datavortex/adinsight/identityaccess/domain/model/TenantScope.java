package io.datavortex.adinsight.identityaccess.domain.model;

import java.util.UUID;

public record TenantScope(UUID tenantId, String userId, TenantRole role) {
    public TenantScope {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(role, "role");
    }
}
