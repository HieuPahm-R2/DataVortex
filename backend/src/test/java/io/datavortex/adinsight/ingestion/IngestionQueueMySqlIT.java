package io.datavortex.adinsight.ingestion;

import static org.assertj.core.api.Assertions.*;

import io.datavortex.adinsight.ingestion.application.port.FetchJobQueue;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestionQueueMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired IngestionJobRepository jobs;
    @Autowired FetchJobQueue queue;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate api;
    @Autowired PasswordEncoder passwords;
    private UUID tenant;
    private UUID otherTenant;
    private UUID account;
    private String email;
    private UUID user;
    private TestRestTemplate authenticatedApi;
    private CookieManager cookieJar;
    private static final String PASSWORD = "integration-only-password";

    @BeforeEach
    void seedOwnedTestDatabase() {
        jdbc.update("DELETE FROM ingestion_jobs");
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        account = UUID.randomUUID();
        user = UUID.randomUUID();
        email = user + "@example.test";
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'A','ACTIVE'),(?,'B','ACTIVE')",
                tenant.toString(),
                otherTenant.toString());
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,status) VALUES (?,?,?,'ACTIVE')",
                user.toString(),
                email,
                passwords.encode(PASSWORD));
        jdbc.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'TENANT_ADMIN'),(?,?,'TENANT_ADMIN')",
                tenant.toString(),
                user.toString(),
                otherTenant.toString(),
                user.toString());
        jdbc.update(
                """
                INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status)
                VALUES (?,?,'MOCK_REST','001','VND','Asia/Ho_Chi_Minh','ACTIVE')
                """,
                account.toString(),
                tenant.toString());
        cookieJar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var httpClient = HttpClient.newBuilder().cookieHandler(cookieJar).build();
        authenticatedApi =
                new TestRestTemplate(
                        new RestTemplateBuilder()
                                .rootUri(api.getRootUri())
                                .requestFactory(() -> new JdkClientHttpRequestFactory(httpClient)),
                        email,
                        PASSWORD);
    }

    @Test
    void concurrentDuplicateSubmissionHasOneIdentityAndSequence() throws Exception {
        try (var workers = Executors.newFixedThreadPool(6)) {
            List<Callable<IngestionJob>> tasks =
                    java.util.stream.IntStream.range(0, 12)
                            .mapToObj(
                                    index ->
                                            (Callable<IngestionJob>)
                                                    () -> jobs.submit(request("same-key")))
                            .toList();
            var results = workers.invokeAll(tasks);
            UUID expected = results.getFirst().get().id();
            for (var result : results) {
                assertThat(result.get().id()).isEqualTo(expected);
                assertThat(result.get().runSequence()).isEqualTo(1);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_jobs", Integer.class))
                .isEqualTo(1);
        IngestionJob next = jobs.submit(request("next-key"));
        assertThat(next.runSequence()).isEqualTo(2);
        assertThat(Duration.between(next.createdAt(), Instant.now()).abs())
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void changedPayloadConflictsAndKeysAreCaseSensitive() {
        jobs.submit(request("Key"));
        var changed =
                new IngestionRequest(
                        tenant, account, ReportKind.KEYWORD, request("Key").dates(), "Key");
        assertCode(() -> jobs.submit(changed), BusinessException.Code.IDEMPOTENCY_CONFLICT);
        assertThat(jobs.submit(request("key")).runSequence()).isEqualTo(2);
    }

    @Test
    void concurrentClaimHasOneOwnerAndExpiredWorkerCannotWrite() throws Exception {
        IngestionJob submitted = jobs.submit(request("lease"));
        JobLease first;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures =
                    workers.invokeAll(
                            List.<Callable<Optional<JobLease>>>of(
                                    () -> queue.claimNext("worker-a", Duration.ofMinutes(1)),
                                    () -> queue.claimNext("worker-b", Duration.ofMinutes(1))));
            var a = futures.get(0).get();
            var b = futures.get(1).get();
            assertThat(a.isPresent() ^ b.isPresent()).isTrue();
            first = a.orElseGet(b::orElseThrow);
        }
        assertThat(first.job().id()).isEqualTo(submitted.id());
        assertThat(first.job().externalJobId(IngestionStage.LOAD))
                .isEqualTo(submitted.externalJobId(IngestionStage.LOAD));
        // Simulate process death without sleeping or weakening production lease predicates.
        jdbc.update(
                "UPDATE ingestion_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                submitted.id().toString());
        assertCode(
                () -> queue.renew(first, Duration.ofMinutes(1)), BusinessException.Code.LEASE_LOST);
        JobLease recovered = queue.claimNext("recovery", Duration.ofMinutes(1)).orElseThrow();
        assertThat(recovered.generation()).isGreaterThan(first.generation());
        assertThat(recovered.token()).isNotEqualTo(first.token());
        assertThat(recovered.job().attempt()).isEqualTo(first.job().attempt());
        assertThat(recovered.job().externalJobId(IngestionStage.LOAD))
                .isEqualTo(first.job().externalJobId(IngestionStage.LOAD));
        assertCode(() -> queue.fail(first, "TIMEOUT"), BusinessException.Code.LEASE_LOST);
        assertCode(
                () -> queue.retryAfter(first, Duration.ofSeconds(1), "TIMEOUT"),
                BusinessException.Code.LEASE_LOST);
        queue.renew(recovered, Duration.ofMinutes(2));
        queue.fail(recovered, "NEEDS_AUTH");
        assertThat(jobs.find(tenant, submitted.id()).orElseThrow().status())
                .isEqualTo(IngestionStatus.FAILED);
        assertThat(queue.claimNext("third", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void retriesAreDeferredCappedAndDoNotPublishCheckpoints() {
        IngestionJob submitted = jobs.submit(request("retry"));
        for (int retry = 0; retry <= IngestionJob.MAX_RETRIES; retry++) {
            JobLease lease = queue.claimNext("worker", Duration.ofMinutes(1)).orElseThrow();
            queue.retryAfter(lease, Duration.ofMinutes(1), "SOURCE_RATE_LIMIT");
            IngestionJob result = jobs.find(tenant, submitted.id()).orElseThrow();
            if (retry == IngestionJob.MAX_RETRIES) {
                assertThat(result.status()).isEqualTo(IngestionStatus.FAILED);
                assertThat(result.attempt()).isEqualTo(IngestionJob.MAX_RETRIES);
            } else {
                assertThat(result.status()).isEqualTo(IngestionStatus.RETRY_WAIT);
                assertThat(queue.claimNext("early", Duration.ofMinutes(1))).isEmpty();
                jdbc.update(
                        "UPDATE ingestion_jobs SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                        submitted.id().toString());
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM publication_watermarks", Integer.class))
                .isZero();
    }

    @Test
    void httpSubmissionUsesCsrfTenantScopeAndReturnsDurableStatus() {
        HttpHeaders headers = csrfHeaders();
        headers.set("Idempotency-Key", "http-key");
        Map<String, Object> body =
                Map.of(
                        "accountId",
                        account,
                        "reportKind",
                        "CAMPAIGN",
                        "dateFrom",
                        "2026-10-01",
                        "dateTo",
                        "2026-10-14",
                        "tenantId",
                        otherTenant);
        var client = authenticatedApi;
        var response =
                client.exchange(
                        "/api/v1/ingestion-jobs",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<?, ?> value = response.getBody();
        assertThat(value).isNotNull();
        String id = value.get("id").toString();
        assertThat(value.get("status")).isEqualTo("PENDING");
        assertThat(value.containsKey("leaseToken")).isFalse();
        assertThat(response.getHeaders().getLocation()).hasToString("/api/v1/ingestion-jobs/" + id);
        assertThat(jobs.find(tenant, UUID.fromString(id))).isPresent();
        var duplicate =
                client.exchange(
                        "/api/v1/ingestion-jobs",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        assertThat(duplicate.getStatusCode())
                .as("Duplicate submission: %s", duplicate.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(duplicate.getBody().get("id")).isEqualTo(id);
        var changed =
                Map.of(
                        "accountId",
                        account,
                        "reportKind",
                        "KEYWORD",
                        "dateFrom",
                        "2026-10-01",
                        "dateTo",
                        "2026-10-14");
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs",
                                        HttpMethod.POST,
                                        new HttpEntity<>(changed, headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        var cookieOnly = new HttpHeaders();
        cookieOnly.set("X-Tenant-Id", tenant.toString());
        cookieOnly.set(
                HttpHeaders.COOKIE, cookieJar.getCookieStore().getCookies().getFirst().toString());
        assertThat(
                        api.exchange(
                                        "/api/v1/ingestion-jobs/" + id,
                                        HttpMethod.GET,
                                        new HttpEntity<>(cookieOnly),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var read =
                client.exchange(
                        "/api/v1/ingestion-jobs/" + id,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers.set("X-Tenant-Id", otherTenant.toString());
        var crossTenant =
                client.exchange(
                        "/api/v1/ingestion-jobs/" + id,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);
        assertThat(crossTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var foreignAccount =
                client.exchange(
                        "/api/v1/ingestion-jobs",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        assertThat(foreignAccount.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void httpRejectsCsrfBypassInvalidInputAndRoleEscalation() {
        var client = authenticatedApi;
        Map<String, Object> body =
                Map.of(
                        "accountId",
                        account,
                        "reportKind",
                        "CAMPAIGN",
                        "dateFrom",
                        "2026-10-01",
                        "dateTo",
                        "2026-10-14");
        var headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant.toString());
        headers.set("Idempotency-Key", "protected");
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs",
                                        HttpMethod.POST,
                                        new HttpEntity<>(body, headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        headers = csrfHeaders();
        headers.set("Idempotency-Key", "invalid");
        var invalid =
                Map.of(
                        "accountId",
                        account,
                        "reportKind",
                        "CAMPAIGN",
                        "dateFrom",
                        "2026-10-15",
                        "dateTo",
                        "2026-10-14");
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs",
                                        HttpMethod.POST,
                                        new HttpEntity<>(invalid, headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        IngestionJob job = jobs.submit(request("existing"));
        jdbc.update(
                "UPDATE memberships SET role='ANALYST' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs",
                                        HttpMethod.POST,
                                        new HttpEntity<>(body, headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs/" + job.id(),
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        jdbc.update(
                "UPDATE memberships SET role='VIEWER' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(
                        client.exchange(
                                        "/api/v1/ingestion-jobs/" + job.id(),
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpHeaders csrfHeaders() {
        var headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant.toString());
        var response =
                authenticatedApi.exchange(
                        "/api/v1/csrf", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var token = response.getBody();
        headers.set(token.get("headerName").toString(), token.get("token").toString());
        assertThat(cookieJar.getCookieStore().getCookies())
                .anyMatch(cookie -> cookie.getName().equals("JSESSIONID"));
        return headers;
    }

    private IngestionRequest request(String key) {
        return new IngestionRequest(
                tenant,
                account,
                ReportKind.CAMPAIGN,
                new DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 14)),
                key);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            BusinessException.Code code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        BusinessException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
