package io.datavortex.adinsight.ingestion.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobLease(IngestionJob job, UUID token, long generation, String owner, Instant until) {
    public JobLease {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(until, "until");
        if (generation < 1
                || owner == null
                || owner.isBlank()
                || owner.length() > 100
                || job.status() != IngestionStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "A lease requires a running job and valid owner/generation");
        }
    }

    public static void validateDuration(Duration duration) {
        if (duration == null
                || duration.isNegative()
                || duration.isZero()
                || duration.toSeconds() < 1) {
            throw new IllegalArgumentException("Lease duration must be at least one second");
        }
    }
}
