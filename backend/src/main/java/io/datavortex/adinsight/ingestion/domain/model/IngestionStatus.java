package io.datavortex.adinsight.ingestion.domain.model;

public enum IngestionStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    FAILED,
    SUCCEEDED
}
