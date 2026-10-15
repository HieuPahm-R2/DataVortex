package io.datavortex.adinsight.ingestion.application.service;

import io.datavortex.adinsight.ingestion.application.port.FetchJobQueue;
import io.datavortex.adinsight.ingestion.application.port.LocalIngestionPipeline;
import io.datavortex.adinsight.ingestion.domain.model.JobLease;
import io.datavortex.adinsight.ingestion.domain.model.PipelineResult;
import java.time.Duration;

/** One local job at a time; the queue owns all state transitions and lease fencing. */
public final class LocalIngestionWorker {
    private final FetchJobQueue queue;
    private final LocalIngestionPipeline pipeline;
    private final String owner;
    private final Duration leaseDuration;

    public LocalIngestionWorker(
            FetchJobQueue queue,
            LocalIngestionPipeline pipeline,
            String owner,
            Duration leaseDuration) {
        JobLease.validateDuration(leaseDuration);
        this.queue = queue;
        this.pipeline = pipeline;
        this.owner = owner;
        this.leaseDuration = leaseDuration;
    }

    public boolean pollOnce() {
        var candidate = queue.claimNext(owner, leaseDuration);
        if (candidate.isEmpty()) return false;
        JobLease lease = candidate.orElseThrow();
        long[] nextRenewal = {System.nanoTime()};
        Runnable heartbeat =
                () -> {
                    if (Thread.currentThread().isInterrupted())
                        throw new IllegalStateException("Worker stopped");
                    if (System.nanoTime() >= nextRenewal[0]) {
                        queue.renew(lease, leaseDuration);
                        nextRenewal[0] = System.nanoTime() + leaseDuration.toNanos() / 3;
                    }
                };
        PipelineResult fetched = pipeline.fetch(lease.job(), heartbeat);
        queue.recordArtifacts(lease, fetched);
        if (!fetched.successful()) {
            finishFailure(lease, fetched);
            return true;
        }
        queue.beginNormalization(lease);
        PipelineResult normalized = pipeline.normalize(fetched, heartbeat);
        queue.recordArtifacts(lease, normalized);
        if (normalized.successful()) queue.awaitLoad(lease);
        else finishFailure(lease, normalized);
        return true;
    }

    private void finishFailure(JobLease lease, PipelineResult result) {
        if (result.retryable())
            queue.retryAfter(
                    lease,
                    Duration.ofSeconds(Math.max(1, result.retryAfterSeconds())),
                    result.errorCode());
        else queue.fail(lease, result.errorCode());
    }
}
