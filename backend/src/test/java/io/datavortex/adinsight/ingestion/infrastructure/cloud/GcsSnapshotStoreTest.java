package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import com.google.cloud.storage.*;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class GcsSnapshotStoreTest {
    @TempDir Path root;
    Storage storage = mock(Storage.class);
    BigQuery bigquery = mock(BigQuery.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper json = new ObjectMapper();
    IngestionJob job;
    GcsSnapshotStore store;
    Path normalized;

    @BeforeEach
    void snapshot() throws Exception {
        UUID tenant = UUID.randomUUID(), account = UUID.randomUUID(), run = UUID.randomUUID();
        var bucket = mock(Bucket.class);
        when(bucket.getLocation()).thenReturn("ASIA-SOUTHEAST1");
        when(storage.get(eq("test-bucket"), any(Storage.BucketGetOption[].class)))
                .thenReturn(bucket);
        var dataset = mock(Dataset.class);
        when(dataset.getLocation()).thenReturn("asia-southeast1");
        when(bigquery.getDataset(
                        eq(DatasetId.of("test-project", "adtech")),
                        any(BigQuery.DatasetOption[].class)))
                .thenReturn(dataset);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "platform",
                                        "MOCK_REST",
                                        "currency",
                                        "VND",
                                        "timezone",
                                        "Asia/Ho_Chi_Minh")));
        Map<String, Object> raw =
                new HashMap<>(
                        Map.ofEntries(
                                Map.entry("run_id", run.toString()),
                                Map.entry("tenant_id", tenant.toString()),
                                Map.entry("account_id", account.toString()),
                                Map.entry("platform", "MOCK_REST"),
                                Map.entry("report_kind", "CAMPAIGN"),
                                Map.entry("date_from", "2026-10-01"),
                                Map.entry("date_to", "2026-10-02"),
                                Map.entry("currency", "VND"),
                                Map.entry("account_timezone", "Asia/Ho_Chi_Minh"),
                                Map.entry("run_sequence", 1),
                                Map.entry("fetched_at", "2026-10-15T00:00:00Z"),
                                Map.entry("row_count", 0),
                                Map.entry("page_count", 0),
                                Map.entry("schema_version", 1),
                                Map.entry("complete", true),
                                Map.entry("errors", List.of()),
                                Map.entry("pages", List.of())));
        Path rawPath = root.resolve("raw/manifest.json");
        Files.createDirectories(rawPath.getParent());
        json.writeValue(rawPath.toFile(), raw);
        Path parquet = root.resolve("normalized/campaign.parquet");
        Files.createDirectories(parquet.getParent());
        Files.writeString(parquet, "fixture bytes: upload boundary, not a Parquet encoding test");
        var complete = new HashMap<>(raw);
        complete.put("normalized_row_count", 0);
        complete.put("rejected_count", 0);
        complete.put("normalized_file", "campaign.parquet");
        complete.put("normalized_sha256", sha(parquet));
        complete.put("raw_manifest_sha256", sha(rawPath));
        normalized = parquet.getParent().resolve("manifest.json");
        json.writeValue(normalized.toFile(), complete);
        job =
                new IngestionJob(
                        UUID.randomUUID(),
                        new IngestionRequest(
                                tenant,
                                account,
                                ReportKind.CAMPAIGN,
                                new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
                                "test"),
                        1,
                        IngestionStatus.RUNNING,
                        IngestionStage.LOAD,
                        0,
                        0,
                        0,
                        0,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        rawPath.toString(),
                        normalized.toString(),
                        null);
        store =
                new GcsSnapshotStore(
                        storage,
                        bigquery,
                        jdbc,
                        json,
                        "test-project",
                        "test-bucket",
                        "adtech",
                        "asia-southeast1",
                        root);
    }

    @Test
    void completeSnapshotUsesCreateOnlyObjectsAndVerifiedCrc() throws Exception {
        var snapshot = store.upload(job, () -> {});
        assertThat(snapshot.rawManifestUri())
                .startsWith("gs://test-bucket/raw/platform=MOCK_REST/tenant=");
        assertThat(snapshot.parquetUri()).endsWith("campaign.parquet");
        var options = ArgumentCaptor.forClass(Storage.BlobWriteOption[].class);
        verify(storage, times(3))
                .createFrom(any(BlobInfo.class), any(Path.class), options.capture());
        options.getAllValues()
                .forEach(
                        value ->
                                assertThat(value)
                                        .contains(
                                                Storage.BlobWriteOption.doesNotExist(),
                                                Storage.BlobWriteOption.crc32cMatch()));
    }

    @Test
    void changedParquetIsRejectedBeforeAnyUpload() throws Exception {
        Files.writeString(normalized.getParent().resolve("campaign.parquet"), "corruption");
        assertThatThrownBy(() -> store.upload(job, () -> {}))
                .isInstanceOf(WarehouseFailure.class)
                .hasMessage("PARQUET_CHECKSUM_MISMATCH");
        verify(storage, never())
                .createFrom(
                        any(BlobInfo.class), any(Path.class), any(Storage.BlobWriteOption[].class));
    }

    @Test
    void conflictingCloudObjectCannotBeOverwritten() throws Exception {
        Blob existing = mock(Blob.class);
        when(existing.getMetadata()).thenReturn(Map.of("sha256", "different"));
        when(storage.get(any(BlobId.class))).thenReturn(existing);
        assertThatThrownBy(() -> store.upload(job, () -> {}))
                .isInstanceOf(WarehouseFailure.class)
                .hasMessage("IMMUTABLE_GCS_OBJECT_CONFLICT");
        verify(storage, never())
                .createFrom(
                        any(BlobInfo.class), any(Path.class), any(Storage.BlobWriteOption[].class));
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    @Test
    void replayAcceptsIdenticalObjectsWithoutUploadingThemAgain() throws Exception {
        Map<BlobId, Blob> objects = new HashMap<>();
        when(storage.get(any(BlobId.class))).thenAnswer(call -> objects.get(call.getArgument(0)));
        when(storage.createFrom(
                        any(BlobInfo.class), any(Path.class), any(Storage.BlobWriteOption[].class)))
                .thenAnswer(
                        call -> {
                            BlobInfo info = call.getArgument(0);
                            Path path = call.getArgument(1);
                            Blob blob = mock(Blob.class);
                            when(blob.getMetadata()).thenReturn(info.getMetadata());
                            when(blob.getCrc32c()).thenReturn(info.getCrc32c());
                            when(blob.getSize()).thenReturn(Files.size(path));
                            objects.put(info.getBlobId(), blob);
                            return blob;
                        });
        var first = store.upload(job, () -> {});
        var second = store.upload(job, () -> {});
        assertThat(second).isEqualTo(first);
        verify(storage, times(3))
                .createFrom(
                        any(BlobInfo.class), any(Path.class), any(Storage.BlobWriteOption[].class));
    }
}
