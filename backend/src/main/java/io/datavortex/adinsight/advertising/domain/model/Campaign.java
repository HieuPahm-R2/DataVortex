package io.datavortex.adinsight.advertising.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Campaign(
        UUID id,
        UUID tenantId,
        UUID accountId,
        SourcePlatform platform,
        String sourceCampaignId,
        String name,
        String sourceStatus,
        String localNote,
        List<String> localTags,
        LocalDate startDate,
        LocalDate endDate,
        long searchVersion,
        Instant updatedAt) {
    public Campaign {
        localTags = List.copyOf(localTags);
    }
}
