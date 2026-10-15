package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.IngestionRequest;
import java.util.Optional;
import java.util.UUID;

public interface IngestionJobRepository {
    /** Atomic request deduplication and monotonic per-account/report sequence allocation. */
    IngestionJob submit(IngestionRequest request);

    Optional<IngestionJob> find(UUID tenantId, UUID jobId);
}
