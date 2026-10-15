package io.datavortex.adinsight.ingestion.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record IngestionJob(
        UUID id,
        IngestionRequest request,
        long runSequence,
        IngestionStatus status,
        IngestionStage stage,
        int attempt,
        long rowsReceived,
        long rowsLoaded,
        long rowsRejected,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant nextRetryAt,
        String errorCode,
        String rawManifestUri,
        String normalizedManifestUri,
        String rejectedUri) {
    public static final int MAX_RETRIES = 5;

    public IngestionJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(createdAt, "createdAt");
        if (runSequence < 1
                || attempt < 0
                || attempt > MAX_RETRIES
                || rowsReceived < 0
                || rowsLoaded < 0
                || rowsRejected < 0) {
            throw new IllegalArgumentException(
                    "Invalid ingestion sequence, retry count or quality counters");
        }
    }

    public boolean retriesExhausted() {
        return attempt >= MAX_RETRIES;
    }

    /**
     * Logical request identity retained for queue consumers. Warehouse execution uses the bound raw
     * run and stage attempt in WarehouseTask.remoteJobId(), persisted before any remote submission.
     */
    public String externalJobId(IngestionStage externalStage) {
        if (externalStage != IngestionStage.LOAD && externalStage != IngestionStage.PUBLISH) {
            throw new IllegalArgumentException(
                    "Only warehouse LOAD/PUBLISH use deterministic BigQuery job IDs");
        }
        return "adinsight_"
                + id.toString().replace("-", "")
                + "_"
                + externalStage.name().toLowerCase(java.util.Locale.ROOT)
                + "_"
                + attempt;
    }
}
