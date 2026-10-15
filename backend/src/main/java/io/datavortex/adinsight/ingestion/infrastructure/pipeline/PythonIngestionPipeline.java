package io.datavortex.adinsight.ingestion.infrastructure.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.ingestion.application.port.LocalIngestionPipeline;
import io.datavortex.adinsight.ingestion.domain.model.IngestionJob;
import io.datavortex.adinsight.ingestion.domain.model.PipelineResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL-owned metadata crosses a file boundary into the existing streaming Python pipeline. */
public final class PythonIngestionPipeline implements LocalIngestionPipeline {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Path root;
    private final Path output;
    private final String python;
    private final String endpoint;
    private final Duration requestTimeout;
    private final Duration processTimeout;

    public PythonIngestionPipeline(
            JdbcTemplate jdbc,
            ObjectMapper json,
            Path root,
            Path output,
            String python,
            String endpoint,
            Duration requestTimeout,
            Duration processTimeout) {
        if (requestTimeout.isNegative()
                || requestTimeout.isZero()
                || processTimeout.isNegative()
                || processTimeout.isZero())
            throw new IllegalArgumentException("Pipeline timeouts must be positive");
        this.jdbc = jdbc;
        this.json = json;
        this.root = root.toAbsolutePath().normalize();
        this.output = output.toAbsolutePath().normalize();
        this.python = python;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.processTimeout = processTimeout;
    }

    @Override
    public PipelineResult fetch(IngestionJob job, Runnable heartbeat) {
        heartbeat.run();
        var request = job.request();
        var accounts =
                jdbc.queryForList(
                        """
                SELECT id,tenant_id,platform,source_account_id,currency,timezone
                FROM ad_accounts WHERE tenant_id=? AND id=? AND status='ACTIVE'
                """,
                        request.tenantId().toString(),
                        request.accountId().toString());
        if (accounts.isEmpty()) return failed("ACCOUNT_UNAVAILABLE", false);
        var account = accounts.getFirst();
        if (!java.util.Set.of("MOCK_REST", "MOCK_SOAP").contains(account.get("platform"))
                || !request.reportKind().name().equals("CAMPAIGN"))
            return failed("UNSUPPORTED_SCHEMA", false);
        var campaigns =
                jdbc.queryForList(
                        """
                SELECT id,tenant_id,account_id,source_campaign_id FROM campaigns
                WHERE tenant_id=? AND account_id=?
                """,
                        request.tenantId().toString(),
                        request.accountId().toString());
        var input = new java.util.HashMap<String, Object>(account);
        input.put("account_id", request.accountId().toString());
        input.put("account_timezone", account.get("timezone"));
        input.put("report_kind", request.reportKind().name());
        input.put("date_from", request.dates().from().toString());
        input.put("date_to", request.dates().to().toString());
        input.put("run_sequence", job.runSequence());
        input.put("job_id", job.id().toString());
        input.put("attempt", job.attempt());
        input.put("metadata", Map.of("accounts", accounts, "campaigns", campaigns));
        try {
            Path control = newControl();
            Path source = control.resolve("request.json");
            json.writeValue(source.toFile(), input);
            return execute("fetch", source, control, heartbeat);
        } catch (IOException error) {
            return failed("PIPELINE_IO_ERROR", true);
        }
    }

    @Override
    public PipelineResult normalize(PipelineResult raw, Runnable heartbeat) {
        PipelineResult result;
        try {
            result = execute("normalize", Path.of(raw.rawManifest()), newControl(), heartbeat);
        } catch (IOException error) {
            result = failed("PIPELINE_IO_ERROR", true);
        }
        return new PipelineResult(
                raw.rawManifest(),
                result.normalizedManifest(),
                result.rejectedUri(),
                raw.rowsReceived(),
                result.rowsRejected(),
                result.errorCode(),
                result.retryable(),
                result.retryAfterSeconds());
    }

    private Path newControl() throws IOException {
        Path directory = output.resolve("control").resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        return directory;
    }

    private PipelineResult execute(String action, Path input, Path control, Runnable heartbeat)
            throws IOException {
        Path result = control.resolve("result.json");
        Process process =
                new ProcessBuilder(
                                python,
                                "-m",
                                "pipeline.local_worker",
                                "--action",
                                action,
                                "--input",
                                input.toString(),
                                "--output",
                                output.toString(),
                                "--result",
                                result.toString(),
                                "--endpoint",
                                endpoint,
                                "--timeout",
                                Double.toString(requestTimeout.toMillis() / 1000.0))
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(control.resolve("process.log").toFile())
                        .start();
        long deadline = System.nanoTime() + processTimeout.toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                heartbeat.run();
                if (System.nanoTime() >= deadline) return failed("PIPELINE_TIMEOUT", true);
            }
            heartbeat.run();
            if (process.exitValue() != 0 || !Files.exists(result))
                return failed("PIPELINE_PROCESS_FAILED", true);
            JsonNode value = json.readTree(result.toFile());
            return new PipelineResult(
                    text(value, "raw_manifest"),
                    text(value, "normalized_manifest"),
                    text(value, "rejected_uri"),
                    value.path("rows_received").asLong(),
                    value.path("rows_rejected").asLong(),
                    text(value, "error_code"),
                    value.path("retryable").asBoolean(),
                    value.path("retry_after").asLong(1));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Local worker interrupted", error);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // Ensure recovery never races a still-running owned subprocess.
                process.onExit().join();
            }
        }
    }

    private static String text(JsonNode value, String name) {
        return value.hasNonNull(name) ? value.get(name).asText() : null;
    }

    private static PipelineResult failed(String code, boolean retryable) {
        return new PipelineResult(null, null, null, 0, 0, code, retryable, 1);
    }
}
