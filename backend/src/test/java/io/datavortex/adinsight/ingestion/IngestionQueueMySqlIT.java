package io.datavortex.adinsight.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.datavortex.adinsight.ingestion.application.port.FetchJobQueue;
import io.datavortex.adinsight.ingestion.application.port.IngestionJobRepository;
import io.datavortex.adinsight.ingestion.application.service.LocalIngestionWorker;
import io.datavortex.adinsight.ingestion.domain.model.*;
import io.datavortex.adinsight.ingestion.infrastructure.config.LocalWorkerConfiguration;
import io.datavortex.adinsight.ingestion.infrastructure.pipeline.PythonIngestionPipeline;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("local")
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

    @ParameterizedTest
    @ValueSource(strings = {"MOCK_REST", "MOCK_SOAP"})
    void httpQueueFetchRawAndParquetWaitForCloudPublication(String platform, @TempDir Path output)
            throws Exception {
        jdbc.update("UPDATE ad_accounts SET platform=? WHERE id=?", platform, account.toString());
        UUID campaign = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO campaigns(id,tenant_id,account_id,source_campaign_id,name,source_status,local_tags)
                VALUES (?,?,?,'0001','Demo','ENABLED','[]')
                """,
                campaign.toString(),
                tenant.toString(),
                account.toString());
        var headers = csrfHeaders();
        headers.set("Idempotency-Key", "local-worker");
        var submitted =
                authenticatedApi.exchange(
                        "/api/v1/ingestion-jobs",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "accountId",
                                        account,
                                        "reportKind",
                                        "CAMPAIGN",
                                        "dateFrom",
                                        "2026-10-01",
                                        "dateTo",
                                        "2026-10-14"),
                                headers),
                        Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = UUID.fromString(submitted.getBody().get("id").toString());
        var json = new ObjectMapper();
        var source = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext(
                "/",
                exchange -> {
                    // Fetch takes longer than a lease; the worker must renew while blocked on HTTP.
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response =
                            json.writeValueAsBytes(
                                    Map.of(
                                            "rows",
                                            List.of(
                                                    Map.ofEntries(
                                                            Map.entry(
                                                                    "tenant_id", tenant.toString()),
                                                            Map.entry("platform", platform),
                                                            Map.entry(
                                                                    "account_id",
                                                                    account.toString()),
                                                            Map.entry(
                                                                    "campaign_id",
                                                                    campaign.toString()),
                                                            Map.entry("source_campaign_id", "0001"),
                                                            Map.entry("currency", "VND"),
                                                            Map.entry(
                                                                    "account_timezone",
                                                                    "Asia/Ho_Chi_Minh"),
                                                            Map.entry("report_date", "2026-10-01"),
                                                            Map.entry("impressions", 100),
                                                            Map.entry("clicks", 5),
                                                            Map.entry("conversions", "0.5"),
                                                            Map.entry("cost_micros", 1250000),
                                                            Map.entry(
                                                                    "conversion_value_micros",
                                                                    3750000))),
                                            "complete",
                                            true));
                    // Explicit null cursor is required by the mock protocol.
                    String body = new String(response, java.nio.charset.StandardCharsets.UTF_8);
                    response =
                            (body.substring(0, body.length() - 1) + ",\"next_page\":null}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (platform.equals("MOCK_SOAP")) {
                        assertThat(exchange.getRequestHeaders().getFirst("SOAPAction"))
                                .isEqualTo("GetCampaignReport");
                        String requestXml =
                                new String(
                                        exchange.getRequestBody().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8);
                        assertThat(requestXml)
                                .contains("<source_account_id>001</source_account_id>");
                        var xml =
                                new StringBuilder(
                                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><GetCampaignReportResponse><rows><row>");
                        json.readTree(response)
                                .path("rows")
                                .get(0)
                                .fields()
                                .forEachRemaining(
                                        field ->
                                                xml.append('<')
                                                        .append(field.getKey())
                                                        .append('>')
                                                        .append(field.getValue().asText())
                                                        .append("</")
                                                        .append(field.getKey())
                                                        .append('>'));
                        xml.append(
                                "</row></rows><next_page/><complete>true</complete></GetCampaignReportResponse></s:Body></s:Envelope>");
                        response = xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    exchange.sendResponseHeaders(200, response.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(response);
                    }
                });
        source.start();
        try (var polling = new AnnotationConfigApplicationContext()) {
            Path root = Path.of("..").toAbsolutePath().normalize();
            polling.getEnvironment().setConversionService(new ApplicationConversionService());
            polling.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "local-it",
                                    Map.of(
                                            "ingestion.local.enabled",
                                            "true",
                                            "ingestion.local.root",
                                            root.toString(),
                                            "ingestion.local.output",
                                            output.toString(),
                                            "ingestion.local.python",
                                            pythonExecutable(root),
                                            "ingestion.local.endpoint",
                                            "http://127.0.0.1:" + source.getAddress().getPort(),
                                            "ingestion.local.lease-duration",
                                            "1s",
                                            "ingestion.local.poll-delay",
                                            "50",
                                            "ingestion.local.request-timeout",
                                            "2s",
                                            "ingestion.local.process-timeout",
                                            "30s")));
            polling.registerBean(FetchJobQueue.class, () -> queue);
            polling.registerBean(JdbcTemplate.class, () -> jdbc);
            polling.registerBean(ObjectMapper.class, () -> json);
            polling.register(LocalWorkerConfiguration.class);
            polling.refresh();
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () ->
                                    assertThat(jobs.find(tenant, id).orElseThrow().stage())
                                            .isEqualTo(IngestionStage.LOAD));
            var job = jobs.find(tenant, id).orElseThrow();
            assertThat(job.status()).isEqualTo(IngestionStatus.RUNNING);
            assertThat(job.stage()).isEqualTo(IngestionStage.LOAD);
            assertThat(job.rowsReceived()).isEqualTo(1);
            assertThat(job.rowsLoaded()).isZero();
            assertThat(job.finishedAt()).isNull();
            assertThat(Files.readString(Path.of(job.rawManifestUri())))
                    .contains("\"complete\": true");
            assertThat(Files.readString(Path.of(job.normalizedManifestUri())))
                    .contains("\"normalized_row_count\": 1");
            assertThat(
                            Files.size(
                                    Path.of(job.normalizedManifestUri())
                                            .getParent()
                                            .resolve("campaign.parquet")))
                    .isGreaterThan(0);
            assertThat(queue.claimNext("no-local-work", Duration.ofSeconds(1))).isEmpty();
            var read =
                    authenticatedApi.exchange(
                            "/api/v1/ingestion-jobs/" + id,
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            Map.class);
            assertThat(read.getBody())
                    .containsEntry("stage", "LOAD")
                    .containsEntry("status", "RUNNING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM publication_watermarks", Integer.class))
                    .isZero();
        } finally {
            source.stop(0);
        }
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

    @ParameterizedTest
    @ValueSource(strings = {"partial", "timeout", "currency"})
    void sourceFailuresRetainEvidenceWithoutLoadOrCheckpoint(String scenario, @TempDir Path output)
            throws Exception {
        IngestionJob submitted = jobs.submit(request("failure"));
        var source = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext(
                "/",
                exchange -> {
                    if (scenario.equals("timeout")) {
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    String row =
                            """
                    {"tenant_id":"%s","account_id":"%s","currency":"EUR"}
                    """
                                    .formatted(tenant, account);
                    byte[] payload =
                            ("{\"rows\":"
                                            + (scenario.equals("currency") ? "[" + row + "]" : "[]")
                                            + ",\"next_page\":null,\"complete\":"
                                            + !scenario.equals("partial")
                                            + "}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(200, payload.length);
                        try (var stream = exchange.getResponseBody()) {
                            stream.write(payload);
                        }
                    } catch (java.io.IOException ignored) {
                        exchange.close();
                    }
                });
        source.start();
        try {
            var worker =
                    localWorker(
                            source,
                            output,
                            scenario.equals("timeout")
                                    ? Duration.ofMillis(50)
                                    : Duration.ofSeconds(2));
            assertThat(worker.pollOnce()).isTrue();
            var failed = jobs.find(tenant, submitted.id()).orElseThrow();
            assertThat(failed.rawManifestUri()).isNotNull();
            assertThat(failed.normalizedManifestUri()).isNull();
            assertThat(failed.rowsLoaded()).isZero();
            String previousRaw = failed.rawManifestUri();
            if (scenario.equals("currency")) {
                assertThat(failed.status()).isEqualTo(IngestionStatus.FAILED);
                assertThat(failed.errorCode()).isEqualTo("QUALITY_REJECTED");
                assertThat(failed.rowsRejected()).isEqualTo(1);
                assertThat(Files.readString(Path.of(failed.rejectedUri())))
                        .contains("raw_row", "EUR");
            } else {
                assertThat(failed.status()).isEqualTo(IngestionStatus.RETRY_WAIT);
                assertThat(failed.attempt()).isEqualTo(1);
                assertThat(worker.pollOnce()).isFalse();
                jdbc.update(
                        "UPDATE ingestion_jobs SET next_retry_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                        submitted.id().toString());
                assertThat(worker.pollOnce()).isTrue();
                assertThat(jobs.find(tenant, submitted.id()).orElseThrow().rawManifestUri())
                        .isNotEqualTo(previousRaw);
                assertThat(Files.exists(Path.of(previousRaw))).isTrue();
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM publication_watermarks", Integer.class))
                    .isZero();
        } finally {
            source.stop(0);
        }
    }

    @Test
    void lostLeaseStopsSubprocessAndCrashReclaimStartsFresh(@TempDir Path output) throws Exception {
        IngestionJob submitted = jobs.submit(request("crash"));
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var source = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext(
                "/",
                exchange -> {
                    arrived.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body = "{\"rows\":[],\"next_page\":null,\"complete\":true}".getBytes();
                    try {
                        exchange.sendResponseHeaders(200, body.length);
                        try (var stream = exchange.getResponseBody()) {
                            stream.write(body);
                        }
                    } catch (java.io.IOException ignored) {
                        exchange.close();
                    }
                });
        source.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var worker = localWorker(source, output, Duration.ofSeconds(5));
            var running = executor.submit(worker::pollOnce);
            assertThat(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            jdbc.update(
                    "UPDATE ingestion_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    submitted.id().toString());
            var reclaimed = queue.claimNext("recovery", Duration.ofSeconds(10)).orElseThrow();
            assertThatThrownBy(() -> running.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class);
            var beforeRecovery = jobs.find(tenant, submitted.id()).orElseThrow();
            assertThat(beforeRecovery.stage()).isEqualTo(IngestionStage.FETCH);
            assertThat(beforeRecovery.rawManifestUri()).isNull();
            assertThat(beforeRecovery.attempt()).isZero();
            assertThat(reclaimed.generation()).isEqualTo(2);
            try (var paths = Files.walk(output)) {
                assertThat(
                                paths.filter(
                                                path ->
                                                        path.getFileName()
                                                                .toString()
                                                                .equals("started.json"))
                                        .count())
                        .isEqualTo(1);
            }
            release.countDown();
            // Simulate recovery process death too: the next poll owns a new generation and raw
            // path.
            jdbc.update(
                    "UPDATE ingestion_jobs SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    submitted.id().toString());
            assertThat(worker.pollOnce()).isTrue();
            assertThat(jobs.find(tenant, submitted.id()).orElseThrow().stage())
                    .isEqualTo(IngestionStage.LOAD);
            try (var paths = Files.walk(output)) {
                assertThat(
                                paths.filter(
                                                path ->
                                                        path.getFileName()
                                                                .toString()
                                                                .equals("started.json"))
                                        .count())
                        .isEqualTo(2);
            }
        } finally {
            release.countDown();
            source.stop(0);
        }
    }

    private LocalIngestionWorker localWorker(HttpServer source, Path output, Duration timeout) {
        Path root = Path.of("..").toAbsolutePath().normalize();
        var pipeline =
                new PythonIngestionPipeline(
                        jdbc,
                        new ObjectMapper(),
                        root,
                        output,
                        pythonExecutable(root),
                        "http://127.0.0.1:" + source.getAddress().getPort(),
                        timeout,
                        Duration.ofSeconds(30));
        return new LocalIngestionWorker(queue, pipeline, "recovery-it", Duration.ofSeconds(1));
    }

    private static String pythonExecutable(Path root) {
        return root.resolve(
                        System.getProperty("os.name").startsWith("Windows")
                                ? ".venv/Scripts/python.exe"
                                : ".venv/bin/python")
                .toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RAW_COMPLETE", "NORMALIZE"})
    void crashBeforeLoadRestartsFetchAndRejectsEveryStaleWrite(String stage) {
        var submitted = jobs.submit(request("local-stage-crash"));
        var original = queue.claimNext("original", Duration.ofSeconds(10)).orElseThrow();
        var evidence = new PipelineResult("raw-manifest", null, null, 7, 0, null, false, 1);
        queue.recordArtifacts(original, evidence);
        queue.beginNormalization(original);
        jdbc.update(
                "UPDATE ingestion_jobs SET stage=?, lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                stage,
                submitted.id().toString());
        var recovered = queue.claimNext("recovered", Duration.ofSeconds(10)).orElseThrow();
        assertThat(recovered.job().stage()).isEqualTo(IngestionStage.FETCH);
        assertThat(recovered.job().attempt()).isZero();
        assertThat(recovered.job().rawManifestUri()).isNull();
        assertCode(
                () -> queue.recordArtifacts(original, evidence), BusinessException.Code.LEASE_LOST);
        assertCode(() -> queue.beginNormalization(original), BusinessException.Code.LEASE_LOST);
        assertCode(() -> queue.awaitLoad(original), BusinessException.Code.LEASE_LOST);
        assertThat(jobs.find(tenant, submitted.id()).orElseThrow().stage())
                .isEqualTo(IngestionStage.FETCH);
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
