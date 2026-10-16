package io.datavortex.adinsight.ingestion;

import static org.assertj.core.api.Assertions.*;

import io.datavortex.adinsight.ingestion.application.port.*;
import io.datavortex.adinsight.ingestion.application.service.WarehouseWorker;
import io.datavortex.adinsight.ingestion.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
class WarehouseMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired WarehouseQueue warehouse;
    @Autowired IngestionJobRepository jobs;
    @Autowired FetchJobQueue fetch;
    @Autowired JdbcTemplate jdbc;
    UUID tenant, account;

    @BeforeEach
    void seed() {
        // This container contains only this test's simulated remote jobs.
        jdbc.update(
                "UPDATE warehouse_publisher SET job_id=NULL,lease_token=NULL,lease_owner=NULL,lease_until=NULL");
        jdbc.update("DELETE FROM ingestion_jobs");
        jdbc.update("DELETE FROM ingestion_checkpoints");
        jdbc.update("DELETE FROM publication_watermarks");
        tenant = UUID.randomUUID();
        account = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'Cloud test','ACTIVE')",
                tenant.toString());
        jdbc.update(
                "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status) VALUES (?,?,'MOCK_REST','001','VND','Asia/Ho_Chi_Minh','ACTIVE')",
                account.toString(),
                tenant.toString());
    }

    private IngestionJob ready(String key) {
        var job =
                jobs.submit(
                        new IngestionRequest(
                                tenant,
                                account,
                                ReportKind.CAMPAIGN,
                                new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
                                key));
        var lease = fetch.claimNext("fetch", Duration.ofSeconds(30)).orElseThrow();
        fetch.recordArtifacts(lease, new PipelineResult("raw", null, null, 1, 0, null, false, 1));
        fetch.beginNormalization(lease);
        fetch.recordArtifacts(
                lease, new PipelineResult("raw", "normalized", null, 1, 0, null, false, 1));
        fetch.awaitLoad(lease);
        return job;
    }

    private WarehouseTask claim() {
        due();
        return warehouse.claim("cloud", Duration.ofSeconds(30)).orElseThrow();
    }

    private void due() {
        jdbc.update(
                "UPDATE warehouse_execution SET next_poll_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
    }

    private void expire() {
        jdbc.update(
                "UPDATE warehouse_publisher SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6))");
        due();
    }

    @Test
    void expiredSlotResumesSameJobAndCannotBeStolenByNewerRun() throws Exception {
        var first = ready("first");
        ready("newer");
        var original = claim();
        assertThat(warehouse.claim("other", Duration.ofSeconds(30))).isEmpty();
        expire();
        var recovered = claim();
        assertThat(recovered.lease().job().id()).isEqualTo(first.id());
        assertThat(recovered.lease().generation()).isGreaterThan(original.lease().generation());
        assertThatThrownBy(() -> warehouse.defer(original, Duration.ofSeconds(1), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void deletingAnOccupiedJobCannotFreeItsPublisherFence() {
        var first = ready("protected");
        claim();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM ingestion_jobs WHERE id=?",
                                        first.id().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(warehouse.claim("second", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void workerReconcilesLostSubmitResponseBeforeCheckpointAndNeverReloads() {
        var job = ready("recover");
        var remote = new RemoteCloud();
        remote.loseResponse = true;
        var worker = new WarehouseWorker(warehouse, remote, "worker", Duration.ofSeconds(30));
        for (int i = 0;
                i < 30
                        && jobs.find(tenant, job.id()).orElseThrow().status()
                                != IngestionStatus.SUCCEEDED;
                i++) {
            due();
            assertThat(worker.pollOnce()).isTrue();
            if (jobs.find(tenant, job.id()).orElseThrow().stage() != IngestionStage.CHECKPOINT
                    && jobs.find(tenant, job.id()).orElseThrow().status()
                            != IngestionStatus.SUCCEEDED)
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM ingestion_checkpoints",
                                        Integer.class))
                        .isZero();
        }
        var completed = jobs.find(tenant, job.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(completed.rowsLoaded()).isEqualTo(1);
        assertThat(completed.finishedAt()).isNotNull();
        assertThat(remote.submissions.size()).isEqualTo(4);
        assertThat(remote.submissions).doesNotHaveDuplicates();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_success_run_id FROM ingestion_checkpoints",
                                String.class))
                .isEqualTo(remote.run.toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_run_id FROM publication_watermarks",
                                String.class))
                .isEqualTo(remote.run.toString());
    }

    @Test
    void runningRemoteJobKeepsTheSlotAcrossExpiry() {
        var first = ready("running");
        ready("newer");
        var remote = new RemoteCloud();
        remote.running = true;
        var worker = new WarehouseWorker(warehouse, remote, "first", Duration.ofSeconds(30));
        for (int i = 0; i < 4; i++) {
            due();
            worker.pollOnce();
        }
        assertThat(remote.submissions).hasSize(1);
        expire();
        var recovered = claim();
        assertThat(recovered.lease().job().id()).isEqualTo(first.id());
        assertThat(recovered.command().jobId()).isEqualTo(remote.submissions.getFirst());
        assertThat(recovered.stageAttempt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                .isZero();
    }

    @Test
    void permanentQualityFailureStopsBeforePublication() {
        var job = ready("quality");
        var remote = new RemoteCloud();
        remote.failStep = WarehouseStep.TRANSFORM;
        var worker = new WarehouseWorker(warehouse, remote, "quality", Duration.ofSeconds(30));
        drain(worker, job, 30);
        assertThat(jobs.find(tenant, job.id()).orElseThrow().status())
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(remote.submitted.values())
                .noneMatch(command -> command.step() == WarehouseStep.PUBLISH);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM publication_watermarks", Integer.class))
                .isZero();
    }

    @Test
    void terminalRetriesAreBoundedAndUseNewIdsOnlyAfterFailedOutcome() {
        var job = ready("terminal");
        var remote = new RemoteCloud();
        remote.failStep = WarehouseStep.LOAD;
        remote.retryable = true;
        var worker = new WarehouseWorker(warehouse, remote, "retry", Duration.ofSeconds(30));
        drain(worker, job, 80);
        var failed = jobs.find(tenant, job.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(failed.attempt()).isEqualTo(5);
        assertThat(remote.submissions).hasSize(6).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                .isZero();
    }

    @Test
    void checkpointFailureRollsBackAndRecoveryNeverReexecutesPublication() {
        var job = ready("checkpoint");
        var remote = new RemoteCloud();
        var worker = new WarehouseWorker(warehouse, remote, "checkpoint", Duration.ofSeconds(30));
        for (int i = 0;
                i < 30
                        && jobs.find(tenant, job.id()).orElseThrow().stage()
                                != IngestionStage.CHECKPOINT;
                i++) {
            due();
            worker.pollOnce();
        }
        assertThat(jobs.find(tenant, job.id()).orElseThrow().stage())
                .isEqualTo(IngestionStage.CHECKPOINT);
        int remoteCalls = remote.submissions.size();
        jdbc.execute(
                "ALTER TABLE publication_watermarks ADD CONSTRAINT injected_reject CHECK(published_run_id <> '"
                        + remote.run
                        + "')");
        try {
            due();
            assertThatThrownBy(worker::pollOnce)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jobs.find(tenant, job.id()).orElseThrow().status())
                    .isEqualTo(IngestionStatus.RUNNING);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM publication_watermarks", Integer.class))
                    .isZero();
        } finally {
            jdbc.execute("ALTER TABLE publication_watermarks DROP CHECK injected_reject");
        }
        expire();
        assertThat(worker.pollOnce()).isTrue();
        assertThat(jobs.find(tenant, job.id()).orElseThrow().status())
                .isEqualTo(IngestionStatus.SUCCEEDED);
        assertThat(remote.submissions).hasSize(remoteCalls);
    }

    private void drain(WarehouseWorker worker, IngestionJob job, int limit) {
        for (int i = 0;
                i < limit
                        && jobs.find(tenant, job.id()).orElseThrow().status()
                                == IngestionStatus.RUNNING;
                i++) {
            due();
            worker.pollOnce();
        }
    }

    final class RemoteCloud implements WarehouseGateway {
        UUID run = UUID.randomUUID();
        boolean loseResponse;
        boolean running, retryable;
        WarehouseStep failStep;
        List<String> submissions = new ArrayList<>();
        Map<String, WarehouseCommand> submitted = new HashMap<>();

        public WarehousePlan prepare(IngestionJob job, Runnable heartbeat) {
            return new WarehousePlan(
                    new CloudSnapshot(
                            run,
                            tenant,
                            account,
                            "MOCK_REST",
                            job.request().dates().from(),
                            job.request().dates().to(),
                            "VND",
                            "Asia/Ho_Chi_Minh",
                            job.runSequence(),
                            Instant.parse("2026-10-15T00:00:00Z"),
                            1,
                            "a".repeat(64),
                            "gs://test/raw/manifest.json",
                            "gs://test/normalized/campaign.parquet",
                            "gs://test/normalized/manifest.json"),
                    "compilation/1",
                    "ASSERT TRUE",
                    "BEGIN TRANSACTION; COMMIT TRANSACTION;",
                    "test-project",
                    "adtech",
                    "US",
                    1000000);
        }

        public WarehouseCommand command(WarehouseTask task) {
            return new WarehouseCommand(
                    task.remoteJobId(),
                    "test-project",
                    "US",
                    task.step(),
                    "stg_campaign_daily",
                    task.plan().snapshot().parquetUri(),
                    "SELECT 1",
                    task.plan().snapshot(),
                    1000000);
        }

        public RemoteJobOutcome inspect(WarehouseCommand command) {
            if (submitted.containsKey(command.jobId()) && running)
                return RemoteJobOutcome.running();
            if (submitted.containsKey(command.jobId()) && command.step() == failStep)
                return new RemoteJobOutcome(
                        RemoteJobOutcome.State.FAILED, "INJECTED_REMOTE_FAILURE", retryable, 0);
            return submitted.containsKey(command.jobId())
                    ? new RemoteJobOutcome(RemoteJobOutcome.State.SUCCEEDED, null, false, 1)
                    : RemoteJobOutcome.missing();
        }

        public void submit(WarehouseCommand command) {
            submissions.add(command.jobId());
            submitted.put(command.jobId(), command);
            if (loseResponse) {
                loseResponse = false;
                throw new WarehouseFailure("REMOTE_RESPONSE_LOST", true);
            }
        }
    }
}
