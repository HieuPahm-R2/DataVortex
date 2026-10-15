package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.PipelineResult;

public interface LocalIngestionPipeline {
    PipelineResult fetch(IngestionJob job, Runnable heartbeat);

    PipelineResult normalize(PipelineResult raw, Runnable heartbeat);
}
