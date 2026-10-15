package io.datavortex.adinsight.advertising.domain.model;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record AdAccount(
        UUID id,
        UUID tenantId,
        SourcePlatform platform,
        String sourceAccountId,
        Currency currency,
        ZoneId timezone,
        String status) {
    public AdAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(timezone, "timezone");
        if (sourceAccountId == null
                || sourceAccountId.isBlank()
                || status == null
                || status.isBlank()) {
            throw new IllegalArgumentException("Source account ID and status are required");
        }
    }
}
