package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.JobLease;
import io.datavortex.adinsight.ingestion.domain.model.PipelineResult;
import java.time.Duration;
import java.util.Optional;

/** Local pre-load leases only. Remote publication needs its own reconciliation/fencing protocol. */
public interface FetchJobQueue {
    Optional<JobLease> claimNext(String worker, Duration duration);

    JobLease renew(JobLease lease, Duration duration);

    /** Call after a transient local pipeline failure; source pages restart into a fresh raw run. */
    void retryAfter(JobLease lease, Duration delay, String errorCode);

    void fail(JobLease lease, String errorCode);

    void recordArtifacts(JobLease lease, PipelineResult result);

    void beginNormalization(JobLease lease);

    /** Park verified local artifacts at LOAD; never marks success or advances a checkpoint. */
    void awaitLoad(JobLease lease);
}
