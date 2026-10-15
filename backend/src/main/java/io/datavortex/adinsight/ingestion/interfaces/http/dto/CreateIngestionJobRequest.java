package io.datavortex.adinsight.ingestion.interfaces.http.dto;

import io.datavortex.adinsight.ingestion.domain.model.ReportKind;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record CreateIngestionJobRequest(
        @NotNull UUID accountId,
        @NotNull ReportKind reportKind,
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo) {}
