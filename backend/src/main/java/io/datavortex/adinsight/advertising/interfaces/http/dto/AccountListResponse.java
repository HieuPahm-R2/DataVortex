package io.datavortex.adinsight.advertising.interfaces.http.dto;

import java.util.List;

public record AccountListResponse(List<AccountResponse> items, String requestId) {}
