package io.datavortex.adinsight.advertising.interfaces.http.dto;

import java.time.Instant;
import java.util.UUID;

public record KeywordResponse(
        UUID id,
        UUID accountId,
        UUID campaignId,
        UUID adGroupId,
        String platform,
        String sourceKeywordId,
        String text,
        String matchType,
        String status,
        String bidMicros,
        String currency,
        long searchVersion,
        Instant updatedAt,
        String requestId) {}
