package io.datavortex.adinsight.advertising.interfaces.http.mapper;

import io.datavortex.adinsight.advertising.domain.model.*;
import io.datavortex.adinsight.advertising.interfaces.http.dto.*;

public final class MetadataResponseMapper {
    private MetadataResponseMapper() {}

    public static CampaignResponse from(Campaign c, String requestId) {
        return new CampaignResponse(
                c.id(),
                c.accountId(),
                c.platform().name(),
                c.sourceCampaignId(),
                c.name(),
                c.sourceStatus(),
                c.localNote(),
                c.localTags(),
                c.startDate(),
                c.endDate(),
                c.searchVersion(),
                c.updatedAt(),
                requestId);
    }

    public static KeywordResponse from(Keyword k, String requestId) {
        return new KeywordResponse(
                k.id(),
                k.accountId(),
                k.campaignId(),
                k.adGroupId(),
                k.platform().name(),
                k.sourceKeywordId(),
                k.text(),
                k.matchType(),
                k.status(),
                Long.toString(k.bidMicros()),
                k.currency().getCurrencyCode(),
                k.searchVersion(),
                k.updatedAt(),
                requestId);
    }
}
