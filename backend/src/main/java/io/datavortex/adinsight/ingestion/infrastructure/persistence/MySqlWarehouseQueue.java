package io.datavortex.adinsight.ingestion.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.ingestion.application.port.WarehouseQueue;
import io.datavortex.adinsight.ingestion.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MySqlWarehouseQueue implements WarehouseQueue {
    private static final String OWNED =
            """
        WHERE singleton_id=1 AND job_id=? AND lease_token=? AND lease_generation=?
          AND lease_until>CURRENT_TIMESTAMP(6)
        """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public MySqlWarehouseQueue(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public Optional<WarehouseTask> claim(String owner, Duration duration) {
        JobLease.validateDuration(duration);
        if (owner == null || owner.isBlank() || owner.length() > 100)
            throw new IllegalArgumentException("Worker owner required");
        return transactions.execute(
                tx -> {
                    var slot =
                            jdbc.queryForMap(
                                    "SELECT *, lease_until>CURRENT_TIMESTAMP(6) AS busy FROM warehouse_publisher WHERE singleton_id=1 FOR UPDATE");
                    String id = (String) slot.get("job_id");
                    Object busy = slot.get("busy");
                    if (id != null
                            && (Boolean.TRUE.equals(busy)
                                    || (busy instanceof Number number && number.intValue() != 0)))
                        return Optional.empty();
                    if (id != null
                            && ((Number)
                                                    jdbc.queryForObject(
                                                            "SELECT COUNT(*) FROM warehouse_execution WHERE job_id=? AND next_poll_at<=CURRENT_TIMESTAMP(6)",
                                                            Long.class,
                                                            id))
                                            .longValue()
                                    == 0) return Optional.empty();
                    if (id == null) {
                        var ready =
                                jdbc.queryForList(
                                        """
                    SELECT id FROM ingestion_jobs WHERE status='RUNNING' AND stage='LOAD'
                      AND normalized_manifest_uri IS NOT NULL ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,
                                        String.class);
                        if (ready.isEmpty()) return Optional.empty();
                        id = ready.getFirst();
                        jdbc.update(
                                "INSERT INTO warehouse_execution(job_id,tenant_id) SELECT id,tenant_id FROM ingestion_jobs WHERE id=? ON DUPLICATE KEY UPDATE job_id=job_id",
                                id);
                    }
                    String token = UUID.randomUUID().toString();
                    jdbc.update(
                            """
                UPDATE warehouse_publisher SET job_id=?,lease_token=?,lease_owner=?,
                  lease_generation=lease_generation+1,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE singleton_id=1
                """,
                            id,
                            token,
                            owner,
                            duration.toSeconds());
                    return Optional.of(read(id));
                });
    }

    private WarehouseTask read(String id) {
        var job =
                jdbc.queryForObject(
                        "SELECT " + MySqlIngestionJobs.COLUMNS + " FROM ingestion_jobs WHERE id=?",
                        new IngestionJobRowMapper(),
                        id);
        var slot = jdbc.queryForMap("SELECT * FROM warehouse_publisher WHERE singleton_id=1");
        var state = jdbc.queryForMap("SELECT * FROM warehouse_execution WHERE job_id=?", id);
        return new WarehouseTask(
                new JobLease(
                        job,
                        UUID.fromString((String) slot.get("lease_token")),
                        ((Number) slot.get("lease_generation")).longValue(),
                        (String) slot.get("lease_owner"),
                        ((Timestamp) slot.get("lease_until")).toInstant()),
                WarehouseStep.valueOf((String) state.get("step")),
                ((Number) state.get("stage_attempt")).intValue(),
                decode(state.get("plan_json"), WarehousePlan.class),
                decode(state.get("command_json"), WarehouseCommand.class));
    }

    @Override
    public void renew(WarehouseTask task, Duration duration) {
        JobLease.validateDuration(duration);
        requireOwned(
                jdbc.update(
                        "UPDATE warehouse_publisher SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) "
                                + OWNED,
                        args(task, duration.toSeconds())));
    }

    private void lock(WarehouseTask task) {
        if (jdbc.queryForList(
                        "SELECT job_id FROM warehouse_publisher " + OWNED + " FOR UPDATE",
                        args(task))
                .isEmpty()) lost();
        var state =
                jdbc.queryForMap(
                        "SELECT step,stage_attempt FROM warehouse_execution WHERE job_id=? FOR UPDATE",
                        task.lease().job().id().toString());
        if (!task.step().name().equals(state.get("step"))
                || task.stageAttempt() != ((Number) state.get("stage_attempt")).intValue()) lost();
    }

    @Override
    public void savePlan(WarehouseTask task, WarehousePlan plan) {
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    var job = task.lease().job();
                    var scope = plan.snapshot();
                    if (plan.project() == null
                            || !plan.project().matches("[a-z][a-z0-9-]{4,61}[a-z0-9]")
                            || plan.dataset() == null
                            || !plan.dataset().matches("[A-Za-z_][A-Za-z0-9_]{0,1023}")
                            || plan.location() == null
                            || !plan.location().matches("[A-Za-z0-9-]+")
                            || plan.maximumBytesBilled() <= 0)
                        throw new WarehouseFailure("INVALID_WAREHOUSE_TARGET", false);
                    if (!scope.tenantId().equals(job.request().tenantId())
                            || !scope.accountId().equals(job.request().accountId())
                            || scope.runSequence() != job.runSequence()
                            || !scope.dateFrom().equals(job.request().dates().from())
                            || !scope.dateTo().equals(job.request().dates().to())
                            || scope.rowCount() != job.rowsReceived())
                        throw new WarehouseFailure("WAREHOUSE_SCOPE_MISMATCH", false);
                    requireOwned(
                            jdbc.update(
                                    "UPDATE warehouse_execution SET plan_json=? WHERE job_id=? AND plan_json IS NULL AND command_json IS NULL",
                                    encode(plan),
                                    job.id().toString()));
                    jdbc.update(
                            "UPDATE ingestion_jobs SET transform_id=? WHERE id=?",
                            plan.compilationId(),
                            job.id().toString());
                });
    }

    @Override
    public void saveCommand(WarehouseTask task, WarehouseCommand command) {
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    if (task.plan() == null
                            || !task.remoteJobId().equals(command.jobId())
                            || !task.plan().project().equals(command.project())
                            || !task.plan().location().equals(command.location())
                            || command.step() != task.step()
                            || !command.snapshot().equals(task.plan().snapshot()))
                        throw new WarehouseFailure("WAREHOUSE_COMMAND_SCOPE_MISMATCH", false);
                    requireOwned(
                            jdbc.update(
                                    "UPDATE warehouse_execution SET command_json=? WHERE job_id=? AND command_json IS NULL",
                                    encode(command),
                                    task.lease().job().id().toString()));
                    jdbc.update(
                            "UPDATE ingestion_jobs SET bq_job_ids=JSON_SET(COALESCE(bq_job_ids,JSON_OBJECT()),?,?) WHERE id=?",
                            "$."
                                    + task.step().name().toLowerCase(java.util.Locale.ROOT)
                                    + "_"
                                    + task.stageAttempt(),
                            command.jobId(),
                            task.lease().job().id().toString());
                });
    }

    @Override
    public void advance(WarehouseTask task, long rowsLoaded) {
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    if (task.command() == null)
                        throw new IllegalStateException("Remote command must be persisted");
                    var next = task.step().next();
                    jdbc.update(
                            "UPDATE warehouse_execution SET step=?,stage_attempt=0,command_json=NULL,next_poll_at=CURRENT_TIMESTAMP(6) WHERE job_id=?",
                            next.name(),
                            task.lease().job().id().toString());
                    jdbc.update(
                            "UPDATE ingestion_jobs SET stage=?,rows_loaded=IF(?='LOAD',?,rows_loaded),error_code=NULL WHERE id=?",
                            next.stage().name(),
                            task.step().name(),
                            rowsLoaded,
                            task.lease().job().id().toString());
                    releaseOwner(task);
                });
    }

    @Override
    public void defer(WarehouseTask task, Duration delay, String errorCode) {
        JobLease.validateDuration(delay);
        validateCode(errorCode);
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    jdbc.update(
                            "UPDATE warehouse_execution SET next_poll_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE job_id=?",
                            delay.toSeconds(),
                            task.lease().job().id().toString());
                    jdbc.update(
                            "UPDATE ingestion_jobs SET error_code=? WHERE id=?",
                            errorCode,
                            task.lease().job().id().toString());
                    releaseOwner(task);
                });
    }

    @Override
    public void retryTerminal(WarehouseTask task, String code) {
        validateCode(code);
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    Integer attempt =
                            jdbc.queryForObject(
                                    "SELECT attempt FROM ingestion_jobs WHERE id=? FOR UPDATE",
                                    Integer.class,
                                    task.lease().job().id().toString());
                    if (attempt >= IngestionJob.MAX_RETRIES) {
                        failLocked(task, code);
                        return;
                    }
                    jdbc.update(
                            "UPDATE warehouse_execution SET stage_attempt=stage_attempt+1,retry_count=retry_count+1,command_json=NULL,next_poll_at=TIMESTAMPADD(SECOND,1,CURRENT_TIMESTAMP(6)) WHERE job_id=?",
                            task.lease().job().id().toString());
                    jdbc.update(
                            "UPDATE ingestion_jobs SET attempt=attempt+1,error_code=? WHERE id=?",
                            code,
                            task.lease().job().id().toString());
                    releaseOwner(task);
                });
    }

    @Override
    public void failTerminal(WarehouseTask task, String code) {
        validateCode(code);
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    failLocked(task, code);
                });
    }

    private void failLocked(WarehouseTask task, String code) {
        jdbc.update(
                "UPDATE ingestion_jobs SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                code,
                task.lease().job().id().toString());
        clearSlot(task);
    }

    @Override
    public void complete(WarehouseTask task) {
        transactions.executeWithoutResult(
                tx -> {
                    lock(task);
                    if (task.step() != WarehouseStep.CHECKPOINT || task.plan() == null)
                        throw new IllegalStateException("Confirmed publication required");
                    var scope = task.plan().snapshot();
                    jdbc.update(
                            """
                INSERT INTO ingestion_checkpoints(tenant_id,account_id,report_kind,last_success_date,last_success_run_id)
                VALUES (?,?,'CAMPAIGN',?,?) ON DUPLICATE KEY UPDATE last_success_date=VALUES(last_success_date),last_success_run_id=VALUES(last_success_run_id)
                """,
                            scope.tenantId().toString(),
                            scope.accountId().toString(),
                            scope.dateTo(),
                            scope.runId().toString());
                    jdbc.update(
                            """
                INSERT INTO publication_watermarks(tenant_id,report_kind,published_run_id,data_as_of,date_from,date_to)
                VALUES (?,'CAMPAIGN',?,?,?,?) ON DUPLICATE KEY UPDATE published_run_id=VALUES(published_run_id),data_as_of=VALUES(data_as_of),date_from=VALUES(date_from),date_to=VALUES(date_to)
                """,
                            scope.tenantId().toString(),
                            scope.runId().toString(),
                            Timestamp.from(scope.fetchedAt()),
                            scope.dateFrom(),
                            scope.dateTo());
                    jdbc.update(
                            "UPDATE ingestion_jobs SET status='SUCCEEDED',stage='CHECKPOINT',error_code=NULL,finished_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                            task.lease().job().id().toString());
                    clearSlot(task);
                });
    }

    private void releaseOwner(WarehouseTask task) {
        requireOwned(
                jdbc.update(
                        "UPDATE warehouse_publisher SET lease_token=NULL,lease_owner=NULL,lease_until=NULL "
                                + OWNED,
                        args(task)));
    }

    private void clearSlot(WarehouseTask task) {
        requireOwned(
                jdbc.update(
                        "UPDATE warehouse_publisher SET job_id=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL "
                                + OWNED,
                        args(task)));
    }

    private static Object[] args(WarehouseTask task, Object... prefix) {
        Object[] result = Arrays.copyOf(prefix, prefix.length + 3);
        result[prefix.length] = task.lease().job().id().toString();
        result[prefix.length + 1] = task.lease().token().toString();
        result[prefix.length + 2] = task.lease().generation();
        return result;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode durable warehouse state", e);
        }
    }

    private <T> T decode(Object value, Class<T> type) {
        if (value == null) return null;
        try {
            return json.readValue(value.toString(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid durable warehouse state", e);
        }
    }

    private static void validateCode(String code) {
        if (code != null && !code.matches("[A-Z][A-Z0-9_]{0,99}"))
            throw new IllegalArgumentException("Safe error code required");
    }

    private static void requireOwned(int changed) {
        if (changed != 1) lost();
    }

    private static void lost() {
        throw new BusinessException(
                BusinessException.Code.LEASE_LOST,
                "Warehouse ownership expired or was reclaimed; reconcile the occupied slot");
    }
}
