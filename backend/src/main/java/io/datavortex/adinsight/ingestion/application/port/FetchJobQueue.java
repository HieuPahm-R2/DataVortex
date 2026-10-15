package io.datavortex.adinsight.ingestion.application.port;

import io.datavortex.adinsight.ingestion.domain.model.JobLease;
import java.time.Duration;
import java.util.Optional;

/** Fetch-stage leases only. Remote publication requires its own reconciliation/fencing protocol. */
public interface FetchJobQueue {
    Optional<JobLease> claimNext(String worker, Duration duration);

    JobLease renew(JobLease lease, Duration duration);

    /**
     * Call only after a transient FETCH failure; source pages must restart into a fresh raw run.
     */
    void retryAfter(JobLease lease, Duration delay, String errorCode);

    void fail(JobLease lease, String errorCode);
}
