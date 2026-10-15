package io.datavortex.adinsight.ingestion.infrastructure.cloud;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import io.datavortex.adinsight.ingestion.domain.model.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DataformCompilerTest {
    @Test
    void compiledOperationsArePinnedAndAllPagesAreRead() throws Exception {
        HttpClient http = mock(HttpClient.class);
        GoogleCredentials credentials = mock(GoogleCredentials.class);
        when(credentials.getRequestMetadata(any()))
                .thenReturn(Map.of("Authorization", List.of("Bearer test-only")));
        String repository =
                "projects/test-project/locations/asia-southeast1/repositories/adinsight";
        String revision = "a".repeat(40), name = repository + "/compilationResults/compiled-1";
        var responses =
                List.of(
                        response(
                                "{\"name\":\""
                                        + name
                                        + "\",\"resolvedGitCommitSha\":\""
                                        + revision
                                        + "\"}"),
                        response(
                                "{\"compilationResultActions\":[{\"filePath\":\"definitions/staging_quality.sqlx\",\"operations\":{\"queries\":[\"ASSERT TRUE\"]}}],\"nextPageToken\":\"next\"}"),
                        response(
                                "{\"compilationResultActions\":[{\"filePath\":\"definitions/publish_campaign.sqlx\",\"operations\":{\"queries\":[\"BEGIN TRANSACTION; COMMIT TRANSACTION;\"]}}]}"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(responses.get(0), responses.get(1), responses.get(2));
        var compiler =
                new DataformCompiler(
                        http,
                        credentials,
                        new ObjectMapper(),
                        repository,
                        revision,
                        "test-project",
                        "adtech");
        var snapshot =
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
                        Instant.now(),
                        0,
                        "a".repeat(64),
                        "gs://test/raw",
                        "gs://test/parquet",
                        "gs://test/normalized");
        var plan = compiler.compile(snapshot, () -> {});
        assertThat(plan.compilationId()).isEqualTo(name);
        assertThat(plan.qualitySql()).isEqualTo("ASSERT TRUE");
        assertThat(plan.publishSql()).isEqualTo("BEGIN TRANSACTION; COMMIT TRANSACTION;");
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues().get(2).uri().toString())
                .endsWith("pageSize=100&pageToken=next");
    }

    @Test
    void mutableRevisionIsRejectedBeforeCallingDataform() {
        assertThatThrownBy(
                        () ->
                                new DataformCompiler(
                                        mock(HttpClient.class),
                                        mock(GoogleCredentials.class),
                                        new ObjectMapper(),
                                        "projects/test-project/locations/asia-southeast1/repositories/adinsight",
                                        "main",
                                        "test-project",
                                        "adtech"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HttpResponse<String> response(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }
}
