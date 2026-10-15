package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BigQueryWarehouseGatewayTest {
    private final BigQuery client = mock(BigQuery.class);
    private final BigQueryWarehouseGateway gateway =
            new BigQueryWarehouseGateway(
                    client,
                    null,
                    null,
                    new ObjectMapper().findAndRegisterModules(),
                    "project-test",
                    "adtech",
                    "asia-southeast1",
                    10000000);
    private final CloudSnapshot snapshot =
            new CloudSnapshot(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "MOCK_REST",
                    LocalDate.of(2026, 10, 1),
                    LocalDate.of(2026, 10, 2),
                    "VND",
                    "Asia/Ho_Chi_Minh",
                    1,
                    Instant.parse("2026-10-15T00:00:00Z"),
                    1,
                    "a".repeat(64),
                    "gs://demo/raw/manifest.json",
                    "gs://demo/normalized/campaign.parquet",
                    "gs://demo/normalized/manifest.json");

    private WarehouseCommand load() {
        return new WarehouseCommand(
                "deterministic_load_0",
                "project-test",
                "asia-southeast1",
                WarehouseStep.LOAD,
                "project-test.adtech.stg_campaign_daily",
                snapshot.parquetUri(),
                null,
                snapshot,
                10000000);
    }

    @Test
    void parquetLoadIsExplicitAndRejectsBadRecords() {
        gateway.submit(load());
        var info = ArgumentCaptor.forClass(JobInfo.class);
        verify(client).create(info.capture());
        assertThat(info.getValue().getJobId().getJob()).isEqualTo("deterministic_load_0");
        assertThat(info.getValue().getJobId().getLocation()).isEqualTo("asia-southeast1");
        LoadJobConfiguration config = info.getValue().getConfiguration();
        assertThat(config.getWriteDisposition()).isEqualTo(JobInfo.WriteDisposition.WRITE_APPEND);
        assertThat(config.getCreateDisposition()).isEqualTo(JobInfo.CreateDisposition.CREATE_NEVER);
        assertThat(config.getAutodetect()).isFalse();
        assertThat(config.getMaxBadRecords()).isZero();
        assertThat(config.getSchema().getFields().get("conversions").getType())
                .isEqualTo(LegacySQLTypeName.NUMERIC);
        assertThat(config.getLabels()).containsKey("adinsight_request");
    }

    @Test
    void conflictingCreateKeepsTheSameIdentityForReconciliation() {
        when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(409, "exists"));
        gateway.submit(load());
        verify(client, times(1)).create(any(JobInfo.class));
    }

    @Test
    void missingJobIsDifferentFromLookupFailure() {
        assertThat(gateway.inspect(load()).state()).isEqualTo(RemoteJobOutcome.State.MISSING);
        when(client.getJob(any(JobId.class))).thenThrow(new BigQueryException(503, "unknown"));
        assertThatThrownBy(() -> gateway.inspect(load()))
                .isInstanceOf(WarehouseFailure.class)
                .hasMessage("BIGQUERY_LOOKUP_UNAVAILABLE");
    }

    @Test
    void existingJobWithAnotherRequestCannotBeAcceptedAsOurPublication() {
        Job job = mock(Job.class);
        when(job.getConfiguration()).thenReturn(QueryJobConfiguration.of("SELECT 1"));
        when(client.getJob(any(JobId.class))).thenReturn(job);
        assertThatThrownBy(() -> gateway.inspect(load()))
                .isInstanceOf(WarehouseFailure.class)
                .hasMessage("REMOTE_JOB_ID_COLLISION");
    }

    @Test
    void recoveryUsesThePersistedTargetWhenWorkerConfigurationChanges() {
        var request =
                new IngestionRequest(
                        snapshot.tenantId(),
                        snapshot.accountId(),
                        ReportKind.CAMPAIGN,
                        new DateRange(snapshot.dateFrom(), snapshot.dateTo()),
                        "binding");
        var job =
                new IngestionJob(
                        UUID.randomUUID(),
                        request,
                        1,
                        IngestionStatus.RUNNING,
                        IngestionStage.LOAD,
                        0,
                        1,
                        0,
                        0,
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        "raw",
                        "normalized",
                        null);
        var lease = new JobLease(job, UUID.randomUUID(), 1, "test", Instant.now().plusSeconds(30));
        var plan =
                new WarehousePlan(
                        snapshot,
                        "compilation/1",
                        "ASSERT TRUE",
                        "SELECT 1",
                        "original-project",
                        "original_dataset",
                        "US",
                        20000000);
        var command = gateway.command(new WarehouseTask(lease, WarehouseStep.LOAD, 0, plan, null));
        assertThat(command.project()).isEqualTo("original-project");
        assertThat(command.location()).isEqualTo("US");
        gateway.submit(command);
        var info = ArgumentCaptor.forClass(JobInfo.class);
        verify(client).create(info.capture());
        LoadJobConfiguration config = info.getValue().getConfiguration();
        assertThat(config.getDestinationTable())
                .isEqualTo(
                        TableId.of("original-project", "original_dataset", "stg_campaign_daily"));
    }
}
