package io.datavortex.adinsight.ingestion.domain.model;

/** Local artifacts are evidence, not proof of warehouse publication. */
public record PipelineResult(
        String rawManifest,
        String normalizedManifest,
        String rejectedUri,
        long rowsReceived,
        long rowsRejected,
        String errorCode,
        boolean retryable,
        long retryAfterSeconds) {
    public boolean successful() {
        return errorCode == null;
    }
}
