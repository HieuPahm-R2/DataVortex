package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import com.fasterxml.jackson.databind.*;
import com.google.auth.oauth2.GoogleCredentials;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Compiles Dataform operations; execution uses persisted deterministic BigQuery jobs. */
public final class DataformCompiler {
    private final HttpClient http;
    private final GoogleCredentials credentials;
    private final ObjectMapper json;
    private final String repository, revision, project, dataset;

    public DataformCompiler(
            HttpClient http,
            GoogleCredentials credentials,
            ObjectMapper json,
            String repository,
            String revision,
            String project,
            String dataset) {
        if (!repository.matches(
                        "projects/[A-Za-z0-9-]+/locations/[a-z0-9-]+/repositories/[A-Za-z0-9_-]+")
                || !revision.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException(
                    "Dataform repository and immutable git commit SHA required");
        this.http = http;
        this.credentials = credentials;
        this.json = json;
        this.repository = repository;
        this.revision = revision;
        this.project = project;
        this.dataset = dataset;
    }

    public WarehousePlan compile(CloudSnapshot snapshot, Runnable heartbeat) {
        try {
            heartbeat.run();
            JsonNode compiled =
                    call(
                            "POST",
                            "https://dataform.googleapis.com/v1/"
                                    + repository
                                    + "/compilationResults",
                            Map.of(
                                    "gitCommitish",
                                    revision,
                                    "codeCompilationConfig",
                                    Map.of(
                                            "defaultDatabase",
                                            project,
                                            "defaultSchema",
                                            dataset,
                                            "vars",
                                            Map.of(
                                                    "project_id",
                                                    project,
                                                    "dataset",
                                                    dataset,
                                                    "run_id",
                                                    snapshot.runId().toString(),
                                                    "tenant_id",
                                                    snapshot.tenantId().toString(),
                                                    "account_id",
                                                    snapshot.accountId().toString(),
                                                    "date_from",
                                                    snapshot.dateFrom().toString(),
                                                    "date_to",
                                                    snapshot.dateTo().toString()))));
            if (compiled.path("compilationErrors").size() != 0)
                throw new WarehouseFailure("DATAFORM_COMPILATION_FAILED", false);
            if (!revision.equals(compiled.path("resolvedGitCommitSha").asText()))
                throw new WarehouseFailure("DATAFORM_REVISION_MISMATCH", false);
            String name = compiled.path("name").asText();
            if (!name.startsWith(repository + "/compilationResults/")
                    || !name.matches("[A-Za-z0-9_/-]+"))
                throw new WarehouseFailure("INVALID_DATAFORM_COMPILATION", false);
            Map<String, String> queries = new HashMap<>();
            String token = "";
            Set<String> seen = new HashSet<>();
            do {
                if (!seen.add(token)) throw new WarehouseFailure("DATAFORM_PAGINATION_LOOP", false);
                heartbeat.run();
                JsonNode page =
                        call(
                                "GET",
                                "https://dataform.googleapis.com/v1/"
                                        + name
                                        + ":query?pageSize=100"
                                        + (token.isEmpty()
                                                ? ""
                                                : "&pageToken="
                                                        + URLEncoder.encode(
                                                                token,
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)),
                                null);
                for (JsonNode action : page.path("compilationResultActions")) {
                    String file = action.path("filePath").asText();
                    if (!Set.of(
                                    "definitions/staging_quality.sqlx",
                                    "definitions/publish_campaign.sqlx")
                            .contains(file)) continue;
                    JsonNode operations = action.path("operations");
                    if (operations.path("disabled").asBoolean()
                            || !operations.path("queries").isArray()
                            || operations.path("queries").isEmpty())
                        throw new WarehouseFailure("DATAFORM_OPERATION_MISSING", false);
                    List<String> sql = new ArrayList<>();
                    operations.path("queries").forEach(query -> sql.add(query.asText()));
                    if (queries.put(file, String.join(";\n", sql)) != null)
                        throw new WarehouseFailure("DATAFORM_OPERATION_DUPLICATE", false);
                }
                token = page.path("nextPageToken").asText("");
            } while (!token.isEmpty());
            if (queries.size() != 2)
                throw new WarehouseFailure("DATAFORM_OPERATION_MISSING", false);
            return new WarehousePlan(
                    snapshot,
                    name,
                    queries.get("definitions/staging_quality.sqlx"),
                    queries.get("definitions/publish_campaign.sqlx"));
        } catch (IOException error) {
            throw new WarehouseFailure("DATAFORM_UNAVAILABLE", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WarehouseFailure("WORKER_INTERRUPTED", true);
        }
    }

    private JsonNode call(String method, String url, Object body)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        credentials
                .getRequestMetadata(uri)
                .forEach((key, values) -> values.forEach(value -> request.header(key, value)));
        if (body == null) request.GET();
        else
            request.header("Content-Type", "application/json")
                    .method(
                            method,
                            HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2)
            throw new WarehouseFailure(
                    "DATAFORM_HTTP_" + response.statusCode(),
                    response.statusCode() == 429 || response.statusCode() >= 500);
        return json.readTree(response.body());
    }
}
