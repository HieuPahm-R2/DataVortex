package io.datavortex.adinsight.ingestion.interfaces.http.dto;

import io.datavortex.adinsight.ingestion.domain.model.IngestionStage;
import io.datavortex.adinsight.ingestion.domain.model.IngestionStatus;
import io.datavortex.adinsight.ingestion.domain.model.ReportKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record IngestionJobResponse(
        UUID id,
        UUID accountId,
        ReportKind reportKind,
        LocalDate dateFrom,
        LocalDate dateTo,
        IngestionStatus status,
        IngestionStage stage,
        int attempt,
        long runSequence,
        long rowsReceived,
        long rowsLoaded,
        long rowsRejected,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant nextRetryAt,
        String errorCode,
        String requestId) {}
