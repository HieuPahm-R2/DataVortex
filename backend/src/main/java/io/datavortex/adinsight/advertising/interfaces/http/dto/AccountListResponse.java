package io.datavortex.adinsight.advertising.interfaces.http.dto;

import java.util.List;

public record AccountListResponse(
        List<AccountResponse> items, int page, int size, boolean hasMore, String requestId) {}
