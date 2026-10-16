package io.datavortex.adinsight.analytics.infrastructure.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.analytics.application.port.IAnalyticsWarehouse;
import io.datavortex.adinsight.analytics.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class PythonAnalyticsWarehouse implements IAnalyticsWarehouse {
    private final ObjectMapper json;
    private final Path root, database;
    private final String python;
    private final Duration timeout;

    public PythonAnalyticsWarehouse(
            ObjectMapper json, Path root, Path database, String python, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Analytics timeout must be positive");
        this.json = json;
        this.root = root.toAbsolutePath().normalize();
        this.database = database.toAbsolutePath().normalize();
        this.python = python;
        this.timeout = timeout;
    }

    @Override
    public AnalyticsResult query(
            UUID tenant, AnalyticsQuery query, List<AnalyticsAccount> accounts) {
        Path control = null;
        Process process = null;
        try {
            control = Files.createTempDirectory("adinsight-analytics-");
            Path input = control.resolve("request.json"), output = control.resolve("result.json");
            json.writeValue(
                    input.toFile(),
                    Map.of(
                            "tenantId",
                            tenant.toString(),
                            "dateFrom",
                            query.dateFrom().toString(),
                            "dateTo",
                            query.dateTo().toString(),
                            "daily",
                            query.daily(),
                            "accounts",
                            accounts));
            process =
                    new ProcessBuilder(
                                    python,
                                    "-m",
                                    "pipeline.local_analytics",
                                    "--database",
                                    database.toString(),
                                    "--input",
                                    input.toString(),
                                    "--result",
                                    output.toString())
                            .directory(root.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    || process.exitValue() != 0
                    || !Files.isRegularFile(output)) throw unavailable();
            var result = json.readTree(output.toFile());
            if (result.has("errorCode")) throw unavailable();
            return json.treeToValue(result, AnalyticsResult.class);
        } catch (IOException error) {
            throw unavailable();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.onExit().join();
            }
            if (control != null)
                for (String file : List.of("request.json", "result.json", "")) {
                    try {
                        Files.deleteIfExists(file.isEmpty() ? control : control.resolve(file));
                    } catch (IOException ignored) {
                        /* Owned temp files only; no raw data or credentials. */
                    }
                }
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                BusinessException.Code.WAREHOUSE_UNAVAILABLE, "Analytics warehouse unavailable");
    }
}
