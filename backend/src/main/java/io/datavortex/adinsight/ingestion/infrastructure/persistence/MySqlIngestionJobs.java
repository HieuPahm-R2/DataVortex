package io.datavortex.adinsight.ingestion.infrastructure.persistence;

import static io.datavortex.adinsight.shared.domain.BusinessException.Code.*;

import io.datavortex.adinsight.ingestion.application.port.FetchJobQueue;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.IngestionRequest;
import io.datavortex.adinsight.ingestion.domain.model.JobLease;
import io.datavortex.adinsight.ingestion.domain.model.PipelineResult;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MySqlIngestionJobs implements IngestionJobRepository, FetchJobQueue {
    static final String COLUMNS =
            """
            id, tenant_id, account_id, report_kind, date_from, date_to, logical_request_id,
            request_hash, status, stage, attempt, run_sequence, rows_received, rows_loaded,
            rows_rejected, created_at, started_at, finished_at, next_retry_at, error_code,
            lease_token, lease_owner, lease_generation, lease_until,
            raw_manifest_uri, normalized_manifest_uri, rejected_uri
            """;
    private static final String LEASE_PREDICATE =
            """
            WHERE id = ? AND tenant_id = ? AND lease_token = ? AND lease_generation = ?
              AND status = 'RUNNING' AND stage IN ('FETCH','RAW_COMPLETE','NORMALIZE')
              AND lease_until > CURRENT_TIMESTAMP(6)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IngestionJobRowMapper mapper = new IngestionJobRowMapper();

    public MySqlIngestionJobs(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public IngestionJob submit(IngestionRequest request) {
        return transactions.execute(
                transaction -> {
                    // Serialize keys within a tenant; the MVP favors predictable deduplication over
                    // submit throughput.
                    jdbc.queryForObject(
                            "SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                            String.class,
                            request.tenantId().toString());
                    var existing =
                            jdbc.query(
                                    "SELECT "
                                            + COLUMNS
                                            + " FROM ingestion_jobs WHERE tenant_id = ? "
                                            + "AND logical_request_id = ?",
                                    mapper,
                                    request.tenantId().toString(),
                                    request.idempotencyKey());
                    if (!existing.isEmpty()) {
                        IngestionJob job = existing.getFirst();
                        if (!job.request().fingerprint().equals(request.fingerprint())) {
                            throw new BusinessException(
                                    IDEMPOTENCY_CONFLICT,
                                    "Idempotency-Key already used for another scope");
                        }
                        return job;
                    }
                    long sequence = allocateSequence(request);
                    UUID id = UUID.randomUUID();
                    jdbc.update(
                            """
                    INSERT INTO ingestion_jobs(id,tenant_id,account_id,report_kind,date_from,date_to,
                      logical_request_id,request_hash,run_sequence)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,
                            id.toString(),
                            request.tenantId().toString(),
                            request.accountId().toString(),
                            request.reportKind().name(),
                            request.dates().from(),
                            request.dates().to(),
                            request.idempotencyKey(),
                            request.fingerprint(),
                            sequence);
                    return find(request.tenantId(), id).orElseThrow();
                });
    }

    private long allocateSequence(IngestionRequest request) {
        Object[] scope = {
            request.tenantId().toString(),
            request.accountId().toString(),
            request.reportKind().name()
        };
        jdbc.update(
                """
                INSERT INTO ingestion_run_sequences(tenant_id,account_id,report_kind,next_sequence)
                VALUES (?,?,?,1) ON DUPLICATE KEY UPDATE next_sequence = next_sequence
                """,
                scope);
        Long sequence =
                jdbc.queryForObject(
                        """
                SELECT next_sequence FROM ingestion_run_sequences
                WHERE tenant_id=? AND account_id=? AND report_kind=? FOR UPDATE
                """,
                        Long.class,
                        scope);
        jdbc.update(
                """
                UPDATE ingestion_run_sequences SET next_sequence=next_sequence+1
                WHERE tenant_id=? AND account_id=? AND report_kind=?
                """,
                scope);
        return java.util.Objects.requireNonNull(sequence);
    }

    @Override
    public Optional<IngestionJob> find(UUID tenantId, UUID jobId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM ingestion_jobs WHERE tenant_id=? AND id=?",
                        mapper,
                        tenantId.toString(),
                        jobId.toString())
                .stream()
                .findFirst();
    }

    @Override
    public Optional<JobLease> claimNext(String worker, Duration duration) {
        JobLease.validateDuration(duration);
        if (worker == null || worker.isBlank() || worker.length() > 100) {
            throw new IllegalArgumentException("Worker owner must contain 1 to 100 characters");
        }
        return transactions.execute(
                transaction -> {
                    List<IngestionJob> ready =
                            jdbc.query(
                                    "SELECT "
                                            + COLUMNS
                                            + """
                     FROM ingestion_jobs WHERE stage IN ('FETCH','RAW_COMPLETE','NORMALIZE') AND (
                       status='PENDING'
                       OR (status='RETRY_WAIT' AND next_retry_at <= CURRENT_TIMESTAMP(6))
                       OR (status='RUNNING' AND lease_until <= CURRENT_TIMESTAMP(6)))
                     ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,
                                    mapper);
                    if (ready.isEmpty()) {
                        return Optional.empty();
                    }
                    IngestionJob job = ready.getFirst();
                    UUID token = UUID.randomUUID();
                    jdbc.update(
                            """
                    UPDATE ingestion_jobs SET status='RUNNING', stage='FETCH', lease_owner=?, lease_token=?,
                      raw_manifest_uri=NULL, normalized_manifest_uri=NULL, rejected_uri=NULL,
                      rows_received=0, rows_rejected=0, error_code=NULL,
                      lease_generation=lease_generation+1,
                      lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),
                      started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)), next_retry_at=NULL
                    WHERE id=? AND tenant_id=?
                    """,
                            worker,
                            token.toString(),
                            duration.toSeconds(),
                            job.id().toString(),
                            job.request().tenantId().toString());
                    return Optional.of(readLease(job));
                });
    }

    @Override
    public JobLease renew(JobLease lease, Duration duration) {
        JobLease.validateDuration(duration);
        return transactions.execute(
                transaction -> {
                    int changed =
                            jdbc.update(
                                    """
                    UPDATE ingestion_jobs SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))
                    """
                                            + LEASE_PREDICATE,
                                    prepend(duration.toSeconds(), leaseArguments(lease)));
                    requireOwned(changed);
                    return readLease(lease.job());
                });
    }

    @Override
    public void retryAfter(JobLease lease, Duration delay, String errorCode) {
        JobLease.validateDuration(delay);
        validateErrorCode(errorCode);
        transactions.executeWithoutResult(
                transaction -> {
                    // Ownership and the attempt counter are read under the same row lock.
                    IngestionJob current = ownedJob(lease);
                    if (current.retriesExhausted()) {
                        fail(lease, errorCode);
                        return;
                    }
                    int changed =
                            jdbc.update(
                                    """
                    UPDATE ingestion_jobs SET status='RETRY_WAIT', stage='FETCH', attempt=attempt+1,
                      next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)), error_code=?,
                      lease_token=NULL, lease_owner=NULL, lease_until=NULL
                    """
                                            + LEASE_PREDICATE,
                                    prepend(
                                            delay.toSeconds(),
                                            prepend(errorCode, leaseArguments(lease))));
                    requireOwned(changed);
                });
    }

    @Override
    public void fail(JobLease lease, String errorCode) {
        validateErrorCode(errorCode);
        int changed =
                jdbc.update(
                        """
                UPDATE ingestion_jobs SET status='FAILED', error_code=?, finished_at=CURRENT_TIMESTAMP(6),
                  lease_token=NULL, lease_owner=NULL, lease_until=NULL
                """
                                + LEASE_PREDICATE,
                        prepend(errorCode, leaseArguments(lease)));
        requireOwned(changed);
    }

    private IngestionJob ownedJob(JobLease lease) {
        return jdbc
                .query(
                        "SELECT "
                                + COLUMNS
                                + " FROM ingestion_jobs "
                                + LEASE_PREDICATE
                                + " FOR UPDATE",
                        mapper,
                        leaseArguments(lease))
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        LEASE_LOST, "Worker lease expired or was reclaimed"));
    }

    @Override
    public void recordArtifacts(JobLease lease, PipelineResult result) {
        requireOwned(
                jdbc.update(
                        """
                UPDATE ingestion_jobs SET raw_manifest_uri=?, normalized_manifest_uri=?,
                  rejected_uri=?, rows_received=?, rows_rejected=?
                """
                                + LEASE_PREDICATE,
                        prepend(
                                result.rawManifest(),
                                prepend(
                                        result.normalizedManifest(),
                                        prepend(
                                                result.rejectedUri(),
                                                prepend(
                                                        result.rowsReceived(),
                                                        prepend(
                                                                result.rowsRejected(),
                                                                leaseArguments(lease))))))));
    }

    @Override
    public void beginNormalization(JobLease lease) {
        transactions.executeWithoutResult(
                transaction -> {
                    requireOwned(
                            jdbc.update(
                                    "UPDATE ingestion_jobs SET stage='RAW_COMPLETE' "
                                            + LEASE_PREDICATE
                                            + " AND stage='FETCH' AND raw_manifest_uri IS NOT NULL",
                                    leaseArguments(lease)));
                    requireOwned(
                            jdbc.update(
                                    "UPDATE ingestion_jobs SET stage='NORMALIZE' "
                                            + LEASE_PREDICATE
                                            + " AND stage='RAW_COMPLETE'",
                                    leaseArguments(lease)));
                });
    }

    @Override
    public void awaitLoad(JobLease lease) {
        requireOwned(
                jdbc.update(
                        """
                UPDATE ingestion_jobs SET stage='LOAD', lease_token=NULL, lease_owner=NULL, lease_until=NULL
                """
                                + LEASE_PREDICATE
                                + " AND stage='NORMALIZE' AND normalized_manifest_uri IS NOT NULL",
                        leaseArguments(lease)));
    }

    private JobLease readLease(IngestionJob job) {
        return jdbc.queryForObject(
                "SELECT " + COLUMNS + " FROM ingestion_jobs WHERE tenant_id=? AND id=?",
                (row, index) ->
                        new JobLease(
                                mapper.mapRow(row, index),
                                UUID.fromString(row.getString("lease_token")),
                                row.getLong("lease_generation"),
                                row.getString("lease_owner"),
                                IngestionJobRowMapper.instant(row, "lease_until")),
                job.request().tenantId().toString(),
                job.id().toString());
    }

    private static Object[] leaseArguments(JobLease lease) {
        return new Object[] {
            lease.job().id().toString(),
            lease.job().request().tenantId().toString(),
            lease.token().toString(),
            lease.generation()
        };
    }

    private static Object[] prepend(Object value, Object[] tail) {
        Object[] arguments = new Object[tail.length + 1];
        arguments[0] = value;
        System.arraycopy(tail, 0, arguments, 1, tail.length);
        return arguments;
    }

    private static void requireOwned(int changed) {
        if (changed != 1) {
            throw new BusinessException(LEASE_LOST, "Worker lease expired or was reclaimed");
        }
    }

    private static void validateErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException(
                    "Use a stable error code, not a raw dependency message");
        }
    }
}
