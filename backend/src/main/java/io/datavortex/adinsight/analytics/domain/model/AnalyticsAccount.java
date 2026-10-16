package io.datavortex.adinsight.analytics.domain.model;

import java.util.List;
import java.util.UUID;

public record AnalyticsAccount(UUID accountId, String currency, List<UUID> campaignIds) {
    public AnalyticsAccount {
        campaignIds = List.copyOf(campaignIds);
    }
}
