package io.datavortex.adinsight.ingestion.interfaces.http.mapper;

import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.interfaces.http.dto.IngestionJobResponse;

public final class IngestionJobResponseMapper {
    private IngestionJobResponseMapper() {}

    public static IngestionJobResponse from(IngestionJob job, String requestId) {
        return new IngestionJobResponse(
                job.id(),
                job.request().accountId(),
                job.request().reportKind(),
                job.request().dates().from(),
                job.request().dates().to(),
                job.status(),
                job.stage(),
                job.attempt(),
                job.runSequence(),
                job.rowsReceived(),
                job.rowsLoaded(),
                job.rowsRejected(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.nextRetryAt(),
                job.errorCode(),
                requestId);
    }
}
