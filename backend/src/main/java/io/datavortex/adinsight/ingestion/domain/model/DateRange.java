package io.datavortex.adinsight.ingestion.domain.model;

import io.datavortex.adinsight.shared.domain.InvalidInputException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record DateRange(LocalDate from, LocalDate to) {
    public DateRange {
        Objects.requireNonNull(from, "dateFrom");
        Objects.requireNonNull(to, "dateTo");
        if (from.isAfter(to)) {
            throw new InvalidInputException("dateFrom must be on or before dateTo");
        }
        if (ChronoUnit.DAYS.between(from, to) >= 366) {
            throw new InvalidInputException(
                    "Date range must contain at most 366 inclusive days (spec-v1.md section 11)");
        }
    }
}
