package io.datavortex.adinsight.identityaccess.domain.model;

public enum TenantRole {
    TENANT_ADMIN,
    ANALYST,
    VIEWER;

    public boolean canSubmitIngestion() {
        return this == TENANT_ADMIN;
    }

    public boolean canReadIngestion() {
        return this == TENANT_ADMIN || this == ANALYST;
    }
}
