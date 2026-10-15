package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import com.google.cloud.storage.*;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32C;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verify local proof before immutable, resumable GCS upload; manifests are uploaded last. */
public final class GcsSnapshotStore {
    private final Storage storage;
    private final BigQuery bigquery;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final String project, bucket, dataset, location;
    private final Path root;

    public GcsSnapshotStore(
            Storage storage,
            BigQuery bigquery,
            JdbcTemplate jdbc,
            ObjectMapper json,
            String project,
            String bucket,
            String dataset,
            String location,
            Path root) {
        this.storage = storage;
        this.bigquery = bigquery;
        this.jdbc = jdbc;
        this.json = json;
        this.project = project;
        this.bucket = bucket;
        this.dataset = dataset;
        this.location = location;
        this.root = root.toAbsolutePath().normalize();
    }

    public CloudSnapshot upload(IngestionJob job, Runnable heartbeat) {
        try {
            heartbeat.run();
            Bucket remoteBucket = storage.get(bucket);
            Dataset remoteDataset = bigquery.getDataset(DatasetId.of(project, dataset));
            if (remoteBucket == null || remoteDataset == null) reject("CLOUD_RESOURCES_MISSING");
            if (!location.equalsIgnoreCase(remoteBucket.getLocation())
                    || !location.equalsIgnoreCase(remoteDataset.getLocation()))
                reject("CLOUD_LOCATION_MISMATCH");
            Path rawPath = local(job.rawManifestUri()),
                    normalizedPath = local(job.normalizedManifestUri());
            JsonNode raw = json.readTree(rawPath.toFile()),
                    normalized = json.readTree(normalizedPath.toFile());
            UUID run = UUID.fromString(required(raw, "run_id"));
            if (!run.toString().equals(required(raw, "run_id"))) reject("INVALID_RUN_ID");
            var request = job.request();
            if (!required(raw, "tenant_id").equals(request.tenantId().toString())
                    || !required(raw, "account_id").equals(request.accountId().toString())
                    || !required(raw, "report_kind").equals("CAMPAIGN")
                    || !required(raw, "date_from").equals(request.dates().from().toString())
                    || !required(raw, "date_to").equals(request.dates().to().toString())
                    || raw.path("run_sequence").asLong() != job.runSequence()
                    || raw.path("row_count").asLong(-1) != job.rowsReceived()
                    || raw.path("schema_version").asInt() != 1
                    || job.rowsRejected() != 0) reject("CLOUD_SNAPSHOT_SCOPE_MISMATCH");
            if (!raw.path("complete").asBoolean()
                    || !raw.path("errors").isArray()
                    || !raw.path("errors").isEmpty()
                    || !normalized.path("complete").asBoolean()
                    || normalized.path("rejected_count").asLong(-1) != 0
                    || normalized.path("normalized_row_count").asLong(-1) != job.rowsReceived())
                reject("INCOMPLETE_CLOUD_SNAPSHOT");
            for (String field :
                    List.of(
                            "run_id",
                            "tenant_id",
                            "account_id",
                            "platform",
                            "report_kind",
                            "date_from",
                            "date_to",
                            "currency",
                            "account_timezone",
                            "run_sequence",
                            "fetched_at",
                            "row_count",
                            "schema_version",
                            "pages"))
                if (!Objects.equals(raw.get(field), normalized.get(field)))
                    reject("RAW_NORMALIZED_SCOPE_MISMATCH");
            var accounts =
                    jdbc.queryForList(
                            "SELECT platform,currency,timezone FROM ad_accounts WHERE tenant_id=? AND id=? AND status='ACTIVE'",
                            request.tenantId().toString(),
                            request.accountId().toString());
            if (accounts.size() != 1
                    || !Objects.equals(
                            accounts.getFirst().get("platform"), required(raw, "platform"))
                    || !Objects.equals(
                            accounts.getFirst().get("currency"), required(raw, "currency"))
                    || !Objects.equals(
                            accounts.getFirst().get("timezone"), required(raw, "account_timezone")))
                reject("ACCOUNT_METADATA_CHANGED");
            if (!required(normalized, "normalized_file").equals("campaign.parquet"))
                reject("INVALID_NORMALIZED_PATH");
            String rawHash = hash(rawPath, heartbeat).sha256();
            if (!rawHash.equals(required(normalized, "raw_manifest_sha256")))
                reject("RAW_MANIFEST_CHECKSUM_MISMATCH");
            JsonNode pages = raw.path("pages");
            if (!pages.isArray() || pages.size() != raw.path("page_count").asInt(-1))
                reject("PAGE_COUNT_MISMATCH");
            long count = 0;
            Set<String> names = new HashSet<>();
            String suffix =
                    "platform="
                            + required(raw, "platform")
                            + "/tenant="
                            + request.tenantId()
                            + "/account="
                            + request.accountId()
                            + "/report=CAMPAIGN/run="
                            + run
                            + "/";
            // Validate every local page before exposing a complete cloud manifest.
            for (JsonNode page : pages) {
                String name = required(page, "file");
                if (!name.matches("page-[0-9]{5}\\.jsonl\\.gz") || !names.add(name))
                    reject("INVALID_RAW_PAGE_PATH");
                Path file = local(rawPath.getParent().resolve(name).toString());
                if (!hash(file, heartbeat).sha256().equals(required(page, "sha256")))
                    reject("RAW_PAGE_CHECKSUM_MISMATCH");
                if (page.path("row_count").asLong(-1) < 0) reject("PAGE_ROW_COUNT_MISMATCH");
                count = Math.addExact(count, page.path("row_count").asLong());
            }
            if (count != job.rowsReceived()) reject("RAW_ROW_COUNT_MISMATCH");
            Path parquet = local(normalizedPath.getParent().resolve("campaign.parquet").toString());
            if (!hash(parquet, heartbeat)
                    .sha256()
                    .equals(required(normalized, "normalized_sha256")))
                reject("PARQUET_CHECKSUM_MISMATCH");
            for (JsonNode page : pages)
                put(
                        local(rawPath.getParent().resolve(required(page, "file")).toString()),
                        "raw/" + suffix + required(page, "file"),
                        heartbeat);
            Path metadata = rawPath.getParent().resolve("metadata.json");
            if (Files.isRegularFile(metadata))
                put(local(metadata.toString()), "raw/" + suffix + "metadata.json", heartbeat);
            put(parquet, "normalized/" + suffix + "campaign.parquet", heartbeat);
            put(rawPath, "raw/" + suffix + "manifest.json", heartbeat);
            put(normalizedPath, "normalized/" + suffix + "manifest.json", heartbeat);
            return new CloudSnapshot(
                    run,
                    request.tenantId(),
                    request.accountId(),
                    required(raw, "platform"),
                    request.dates().from(),
                    request.dates().to(),
                    required(raw, "currency"),
                    required(raw, "account_timezone"),
                    job.runSequence(),
                    Instant.parse(required(raw, "fetched_at")),
                    count,
                    rawHash,
                    uri("raw/" + suffix + "manifest.json"),
                    uri("normalized/" + suffix + "campaign.parquet"),
                    uri("normalized/" + suffix + "manifest.json"));
        } catch (StorageException | BigQueryException error) {
            throw new WarehouseFailure("CLOUD_STORAGE_OR_DATASET_UNAVAILABLE", true);
        } catch (IOException error) {
            throw new WarehouseFailure("LOCAL_ARTIFACT_UNAVAILABLE", false);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new WarehouseFailure("INVALID_CLOUD_SNAPSHOT", false);
        }
    }

    private Path local(String value) throws IOException {
        if (value == null) reject("LOCAL_ARTIFACT_MISSING");
        Path resolved = Path.of(value).toRealPath();
        if (!resolved.startsWith(root.toRealPath())) reject("ARTIFACT_OUTSIDE_CONFIGURED_ROOT");
        return resolved;
    }

    private void put(Path path, String name, Runnable heartbeat) throws IOException {
        heartbeat.run();
        var digest = hash(path, heartbeat);
        BlobId id = BlobId.of(bucket, name);
        Blob existing = storage.get(id);
        if (existing != null) {
            same(existing, path, digest);
            return;
        }
        BlobInfo info =
                BlobInfo.newBuilder(id)
                        .setContentType(
                                name.endsWith(".gz")
                                        ? "application/gzip"
                                        : name.endsWith(".parquet")
                                                ? "application/octet-stream"
                                                : "application/json")
                        .setMetadata(Map.of("sha256", digest.sha256()))
                        .setCrc32c(digest.crc32c())
                        .build();
        try {
            storage.createFrom(
                    info,
                    path,
                    Storage.BlobWriteOption.doesNotExist(),
                    Storage.BlobWriteOption.crc32cMatch());
        } catch (StorageException error) {
            if (error.getCode() != 412) throw error;
            Blob raced = storage.get(id);
            if (raced == null) throw new WarehouseFailure("GCS_UPLOAD_OUTCOME_UNKNOWN", true);
            same(raced, path, digest);
        }
    }

    private static void same(Blob blob, Path path, Digest digest) throws IOException {
        if (blob.getMetadata() == null
                || !digest.sha256().equals(blob.getMetadata().get("sha256"))
                || !digest.crc32c().equals(blob.getCrc32c())
                || blob.getSize() != Files.size(path)) reject("IMMUTABLE_GCS_OBJECT_CONFLICT");
    }

    private record Digest(String sha256, String crc32c) {}

    private static Digest hash(Path path, Runnable heartbeat) throws IOException {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            var crc = new CRC32C();
            byte[] bytes = new byte[1 << 20];
            try (var stream = Files.newInputStream(path)) {
                int read;
                while ((read = stream.read(bytes)) != -1) {
                    sha.update(bytes, 0, read);
                    crc.update(bytes, 0, read);
                    heartbeat.run();
                }
            }
            return new Digest(
                    HexFormat.of().formatHex(sha.digest()),
                    Base64.getEncoder()
                            .encodeToString(
                                    ByteBuffer.allocate(4).putInt((int) crc.getValue()).array()));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String uri(String name) {
        return "gs://" + bucket + "/" + name;
    }

    private static String required(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
            reject("INVALID_MANIFEST_" + field.toUpperCase(Locale.ROOT));
        return node.path(field).asText();
    }

    private static void reject(String code) {
        throw new WarehouseFailure(code, false);
    }
}
