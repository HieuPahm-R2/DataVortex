package io.datavortex.adinsight.ingestion.domain.model;

import java.time.LocalDate;
import java.util.Objects;

public record DateRange(LocalDate from, LocalDate to) {
    public DateRange {
        Objects.requireNonNull(from, "dateFrom");
        Objects.requireNonNull(to, "dateTo");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("dateFrom must be on or before dateTo");
        }
    }
}
