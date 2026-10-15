package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import io.datavortex.adinsight.ingestion.application.port.WarehouseGateway;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.security.*;
import java.util.*;

public final class BigQueryWarehouseGateway implements WarehouseGateway {
    private final BigQuery client;
    private final GcsSnapshotStore artifacts;
    private final DataformCompiler compiler;
    private final ObjectMapper json;
    private final String project, dataset, location;
    private final long maximumBytesBilled;

    public BigQueryWarehouseGateway(
            BigQuery client,
            GcsSnapshotStore artifacts,
            DataformCompiler compiler,
            ObjectMapper json,
            String project,
            String dataset,
            String location,
            long maximumBytesBilled) {
        if (!project.matches("[a-z][a-z0-9-]{4,61}[a-z0-9]")
                || !dataset.matches("[A-Za-z_][A-Za-z0-9_]{0,1023}")
                || !location.matches("[A-Za-z0-9-]+")
                || maximumBytesBilled <= 0)
            throw new IllegalArgumentException(
                    "Explicit cloud project/dataset/location/query byte cap required");
        this.client = client;
        this.artifacts = artifacts;
        this.compiler = compiler;
        this.json = json;
        this.project = project;
        this.dataset = dataset;
        this.location = location;
        this.maximumBytesBilled = maximumBytesBilled;
    }

    @Override
    public WarehousePlan prepare(IngestionJob job, Runnable heartbeat) {
        var compiled = compiler.compile(artifacts.upload(job, heartbeat), heartbeat);
        return new WarehousePlan(
                compiled.snapshot(),
                compiled.compilationId(),
                compiled.qualitySql(),
                compiled.publishSql(),
                project,
                dataset,
                location,
                maximumBytesBilled);
    }

    @Override
    public WarehouseCommand command(WarehouseTask task) {
        var snapshot = task.plan().snapshot();
        String sql =
                switch (task.step()) {
                    case LOAD -> null;
                    case MANIFEST -> manifestSql(task.plan());
                    case TRANSFORM -> task.plan().qualitySql();
                    case PUBLISH -> task.plan().publishSql();
                    case CHECKPOINT ->
                            throw new IllegalArgumentException("Checkpoint is MySQL only");
                };
        return new WarehouseCommand(
                task.remoteJobId(),
                task.plan().project(),
                task.plan().location(),
                task.step(),
                task.plan().project() + "." + task.plan().dataset() + ".stg_campaign_daily",
                snapshot.parquetUri(),
                sql,
                snapshot,
                task.plan().maximumBytesBilled());
    }

    @Override
    public RemoteJobOutcome inspect(WarehouseCommand command) {
        try {
            Job job = client.getJob(id(command));
            if (job == null) return RemoteJobOutcome.missing();
            JobConfiguration configuration = job.getConfiguration();
            Map<String, String> labels =
                    configuration instanceof LoadJobConfiguration load
                            ? load.getLabels()
                            : configuration instanceof QueryJobConfiguration query
                                    ? query.getLabels()
                                    : Map.of();
            if (labels == null || !fingerprint(command).equals(labels.get("adinsight_request")))
                throw new WarehouseFailure("REMOTE_JOB_ID_COLLISION", false);
            if (job.getStatus().getState() != JobStatus.State.DONE)
                return RemoteJobOutcome.running();
            BigQueryError error = job.getStatus().getError();
            if (error != null) {
                String reason = Objects.requireNonNullElse(error.getReason(), "UNKNOWN");
                return new RemoteJobOutcome(
                        RemoteJobOutcome.State.FAILED,
                        "BIGQUERY_" + reason.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_"),
                        Set.of(
                                        "backendError",
                                        "internalError",
                                        "rateLimitExceeded",
                                        "jobRateLimitExceeded")
                                .contains(reason),
                        0);
            }
            long rows = 0;
            if (command.step() == WarehouseStep.LOAD) {
                JobStatistics.LoadStatistics statistics = job.getStatistics();
                if (statistics == null || statistics.getOutputRows() == null)
                    throw new WarehouseFailure("LOAD_STATISTICS_MISSING", true);
                rows = statistics.getOutputRows();
            }
            return new RemoteJobOutcome(RemoteJobOutcome.State.SUCCEEDED, null, false, rows);
        } catch (BigQueryException error) {
            if (error.getCode() == 404) return RemoteJobOutcome.missing();
            throw new WarehouseFailure("BIGQUERY_LOOKUP_UNAVAILABLE", true);
        }
    }

    @Override
    public void submit(WarehouseCommand command) {
        try {
            client.create(JobInfo.of(id(command), configuration(command)));
        } catch (BigQueryException error) {
            if (error.getCode() != 409)
                throw new WarehouseFailure("BIGQUERY_SUBMIT_OUTCOME_UNKNOWN", true);
        }
    }

    private JobConfiguration configuration(WarehouseCommand command) {
        Map<String, String> labels = Map.of("adinsight_request", fingerprint(command));
        String[] destination = command.destination().split("\\.");
        if (command.step() == WarehouseStep.LOAD)
            return LoadJobConfiguration.newBuilder(
                            TableId.of(destination[0], destination[1], destination[2]),
                            command.sourceUri(),
                            FormatOptions.parquet())
                    .setCreateDisposition(JobInfo.CreateDisposition.CREATE_NEVER)
                    .setWriteDisposition(JobInfo.WriteDisposition.WRITE_APPEND)
                    .setAutodetect(false)
                    .setSchema(schema())
                    .setIgnoreUnknownValues(false)
                    .setMaxBadRecords(0)
                    .setLabels(labels)
                    .build();
        var query =
                QueryJobConfiguration.newBuilder(command.sql())
                        .setUseLegacySql(false)
                        .setMaximumBytesBilled(command.maximumBytesBilled())
                        .setLabels(labels);
        parameters(command.snapshot()).forEach(query::addNamedParameter);
        return query.build();
    }

    private static JobId id(WarehouseCommand command) {
        return JobId.newBuilder()
                .setProject(command.project())
                .setLocation(command.location())
                .setJob(command.jobId())
                .build();
    }

    private String fingerprint(WarehouseCommand command) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(command)))
                    .substring(0, 63);
        } catch (NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    public static Schema schema() {
        List<Field> fields = new ArrayList<>();
        for (String name :
                List.of("tenant_id", "platform", "account_id", "campaign_id", "source_campaign_id"))
            fields.add(required(name, StandardSQLTypeName.STRING));
        fields.add(required("report_date", StandardSQLTypeName.DATE));
        fields.add(required("account_timezone", StandardSQLTypeName.STRING));
        fields.add(required("currency", StandardSQLTypeName.STRING));
        for (String name : List.of("impressions", "clicks"))
            fields.add(required(name, StandardSQLTypeName.INT64));
        for (String name : List.of("conversions", "cost", "conversion_value"))
            fields.add(required(name, StandardSQLTypeName.NUMERIC));
        fields.add(required("run_id", StandardSQLTypeName.STRING));
        fields.add(required("run_sequence", StandardSQLTypeName.INT64));
        fields.add(required("fetched_at", StandardSQLTypeName.TIMESTAMP));
        fields.add(required("is_partial", StandardSQLTypeName.BOOL));
        fields.add(required("schema_version", StandardSQLTypeName.INT64));
        return Schema.of(fields);
    }

    private static Field required(String name, StandardSQLTypeName type) {
        return Field.newBuilder(name, type).setMode(Field.Mode.REQUIRED).build();
    }

    private static Map<String, QueryParameterValue> parameters(CloudSnapshot snapshot) {
        var result = new HashMap<String, QueryParameterValue>();
        Map.ofEntries(
                        Map.entry("tenant_id", snapshot.tenantId().toString()),
                        Map.entry("account_id", snapshot.accountId().toString()),
                        Map.entry("platform", snapshot.platform()),
                        Map.entry("run_id", snapshot.runId().toString()),
                        Map.entry("currency", snapshot.currency()),
                        Map.entry("account_timezone", snapshot.accountTimezone()),
                        Map.entry("raw_manifest_sha256", snapshot.rawManifestSha256()))
                .forEach((key, value) -> result.put(key, QueryParameterValue.string(value)));
        result.put("date_from", QueryParameterValue.date(snapshot.dateFrom().toString()));
        result.put("date_to", QueryParameterValue.date(snapshot.dateTo().toString()));
        result.put("run_sequence", QueryParameterValue.int64(snapshot.runSequence()));
        result.put("row_count", QueryParameterValue.int64(snapshot.rowCount()));
        result.put(
                "fetched_at",
                QueryParameterValue.timestamp(
                        java.time.format.DateTimeFormatter.ofPattern(
                                        "yyyy-MM-dd HH:mm:ss.SSSSSSxxx")
                                .withZone(java.time.ZoneOffset.UTC)
                                .format(snapshot.fetchedAt())));
        return result;
    }

    private String manifestSql(WarehousePlan plan) {
        String table = "`" + plan.project() + "." + plan.dataset() + ".ingestion_manifests`";
        return """
            ASSERT NOT EXISTS(SELECT 1 FROM %s WHERE run_id=@run_id AND (
              tenant_id!=@tenant_id OR account_id!=@account_id OR platform!=@platform OR run_sequence!=@run_sequence
              OR raw_manifest_sha256!=@raw_manifest_sha256 OR row_count!=@row_count OR NOT complete
              OR rejected_count!=0 OR currency!=@currency OR account_timezone!=@account_timezone
              OR date_from!=@date_from OR date_to!=@date_to OR fetched_at!=@fetched_at OR schema_version!=1
              OR report_kind!='CAMPAIGN')) AS 'IMMUTABLE_MANIFEST_CONFLICT';
            INSERT INTO %s(run_id,run_sequence,tenant_id,platform,account_id,report_kind,date_from,date_to,
              currency,account_timezone,fetched_at,complete,row_count,rejected_count,schema_version,raw_manifest_sha256)
            SELECT @run_id,@run_sequence,@tenant_id,@platform,@account_id,'CAMPAIGN',@date_from,@date_to,
              @currency,@account_timezone,@fetched_at,TRUE,@row_count,0,1,@raw_manifest_sha256
            WHERE NOT EXISTS(SELECT 1 FROM %s WHERE run_id=@run_id);
            """
                .formatted(table, table, table);
    }
}
