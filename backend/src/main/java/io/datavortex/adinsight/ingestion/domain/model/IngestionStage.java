package io.datavortex.adinsight.ingestion.domain.model;

public enum IngestionStage {
    FETCH,
    RAW_COMPLETE,
    NORMALIZE,
    LOAD,
    TRANSFORM,
    PUBLISH,
    CHECKPOINT
}
