package io.datavortex.adinsight.advertising.domain.model;

import java.util.List;

public record AccountPage(List<AdAccount> items, int page, int size, boolean hasMore) {
    public AccountPage {
        items = List.copyOf(items);
    }
}
