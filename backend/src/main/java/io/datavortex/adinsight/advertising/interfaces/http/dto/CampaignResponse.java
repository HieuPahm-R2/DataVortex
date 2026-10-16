package io.datavortex.adinsight.advertising.interfaces.http.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CampaignResponse(
        UUID id,
        UUID accountId,
        String platform,
        String sourceCampaignId,
        String name,
        String sourceStatus,
        String localNote,
        List<String> localTags,
        LocalDate startDate,
        LocalDate endDate,
        long searchVersion,
        Instant updatedAt,
        String requestId) {}
