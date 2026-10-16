package io.datavortex.adinsight.advertising.domain.model;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public record Keyword(
        UUID id,
        UUID tenantId,
        UUID accountId,
        UUID campaignId,
        UUID adGroupId,
        SourcePlatform platform,
        String sourceKeywordId,
        String text,
        String matchType,
        String status,
        long bidMicros,
        Currency currency,
        long searchVersion,
        Instant updatedAt) {}
