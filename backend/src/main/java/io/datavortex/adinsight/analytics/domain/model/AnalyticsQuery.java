package io.datavortex.adinsight.analytics.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

public record AnalyticsQuery(
        LocalDate dateFrom,
        LocalDate dateTo,
        Currency currency,
        UUID accountId,
        UUID campaignId,
        boolean daily) {
    public AnalyticsQuery {
        if (dateFrom == null
                || dateTo == null
                || dateFrom.isAfter(dateTo)
                || ChronoUnit.DAYS.between(dateFrom, dateTo) >= 366)
            throw new InvalidInputException(
                    "Analytics requires an ordered date range of at most 366 days");
    }
}
