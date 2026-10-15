package io.datavortex.adinsight.advertising.interfaces.http.dto;

public record AccountResponse(
        String id,
        String platform,
        String sourceAccountId,
        String currency,
        String timezone,
        String status) {}
