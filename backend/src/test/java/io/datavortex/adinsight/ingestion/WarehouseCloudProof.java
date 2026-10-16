package io.datavortex.adinsight.ingestion;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpServer;
import io.datavortex.adinsight.ingestion.application.port.*;
import io.datavortex.adinsight.ingestion.application.service.*;
import io.datavortex.adinsight.ingestion.domain.model.*;
import io.datavortex.adinsight.ingestion.infrastructure.cloud.*;
import io.datavortex.adinsight.ingestion.infrastructure.pipeline.PythonIngestionPipeline;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Explicit opt-in: -Dit.test=WarehouseCloudProof. Never included or silently skipped locally. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
class WarehouseCloudProof {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", MYSQL::getJdbcUrl);
        p.add("spring.datasource.username", MYSQL::getUsername);
        p.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired IngestionJobRepository jobs;
    @Autowired FetchJobQueue fetch;
    @Autowired WarehouseQueue queue;

    @Test
    void realCloudReplaysCorrectsAndPublishesAnEmptySnapshot(@TempDir Path output)
            throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Properties settings = new Properties();
        try (var input = Files.newInputStream(root.resolve(".local/cloud.properties"))) {
            settings.load(input);
        }
        String project = setting(settings, "WAREHOUSE_PROJECT"),
                bucket = setting(settings, "WAREHOUSE_BUCKET"),
                dataset = setting(settings, "WAREHOUSE_DATASET"),
                location = setting(settings, "WAREHOUSE_LOCATION");
        String repository = setting(settings, "WAREHOUSE_DATAFORM_REPOSITORY"),
                revision = setting(settings, "WAREHOUSE_DATAFORM_REVISION");
        long cap = Long.parseLong(setting(settings, "WAREHOUSE_MAXIMUM_BYTES_BILLED"));
        UUID tenant = UUID.randomUUID(), account = UUID.randomUUID(), campaign = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'Cloud proof','ACTIVE')",
                tenant.toString());
        jdbc.update(
                "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status) VALUES (?,?,'MOCK_REST','001','VND','Asia/Ho_Chi_Minh','ACTIVE')",
                account.toString(),
                tenant.toString());
        jdbc.update(
                "INSERT INTO campaigns(id,tenant_id,account_id,source_campaign_id,name,source_status,local_tags) VALUES (?,?,?,'0001','Cloud proof','ENABLED','[]')",
                campaign.toString(),
                tenant.toString(),
                account.toString());
        AtomicInteger scenario = new AtomicInteger();
        var source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        source.createContext(
                "/rest/campaigns",
                exchange -> {
                    var row =
                            Map.ofEntries(
                                    Map.entry("tenant_id", tenant.toString()),
                                    Map.entry("account_id", account.toString()),
                                    Map.entry("campaign_id", campaign.toString()),
                                    Map.entry("platform", "MOCK_REST"),
                                    Map.entry("source_campaign_id", "0001"),
                                    Map.entry("report_date", "2026-10-01"),
                                    Map.entry("currency", "VND"),
                                    Map.entry("account_timezone", "Asia/Ho_Chi_Minh"),
                                    Map.entry("impressions", 100),
                                    Map.entry("clicks", 5),
                                    Map.entry("conversions", scenario.get() == 0 ? "0.5" : "1.5"),
                                    Map.entry("cost_micros", 1250000),
                                    Map.entry("conversion_value_micros", 3750000));
                    var page = new HashMap<String, Object>();
                    page.put("rows", scenario.get() == 2 ? List.of() : List.of(row));
                    page.put("next_page", null);
                    page.put("complete", true);
                    byte[] body = json.writeValueAsBytes(page);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var stream = exchange.getResponseBody()) {
                        stream.write(body);
                    }
                });
        var credentials =
                GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
        var bigquery =
                BigQueryOptions.newBuilder()
                        .setProjectId(project)
                        .setLocation(location)
                        .setCredentials(credentials)
                        .build()
                        .getService();
        source.start();
        try (var storage =
                StorageOptions.newBuilder()
                        .setProjectId(project)
                        .setCredentials(credentials)
                        .build()
                        .getService()) {
            var artifacts =
                    new GcsSnapshotStore(
                            storage, bigquery, jdbc, json, project, bucket, dataset, location,
                            output);
            var compiler =
                    new DataformCompiler(
                            HttpClient.newHttpClient(),
                            credentials,
                            json,
                            repository,
                            revision,
                            project,
                            dataset);
            var gateway =
                    new BigQueryWarehouseGateway(
                            bigquery, artifacts, compiler, json, project, dataset, location, cap);
            AtomicReference<WarehouseCommand> firstLoad = new AtomicReference<>();
            WarehouseGateway observed =
                    new WarehouseGateway() {
                        public WarehousePlan prepare(IngestionJob job, Runnable heartbeat) {
                            return gateway.prepare(job, heartbeat);
                        }

                        public WarehouseCommand command(WarehouseTask task) {
                            return gateway.command(task);
                        }

                        public RemoteJobOutcome inspect(WarehouseCommand command) {
                            return gateway.inspect(command);
                        }

                        public void submit(WarehouseCommand command) {
                            if (command.step() == WarehouseStep.LOAD)
                                firstLoad.compareAndSet(null, command);
                            gateway.submit(command);
                        }
                    };
            var cloud =
                    new WarehouseWorker(
                            queue, observed, "real-cloud-proof", Duration.ofSeconds(60));
            String python =
                    root.resolve(
                                    System.getProperty("os.name").startsWith("Windows")
                                            ? ".venv/Scripts/python.exe"
                                            : ".venv/bin/python")
                            .toString();
            var local =
                    new LocalIngestionWorker(
                            fetch,
                            new PythonIngestionPipeline(
                                    jdbc,
                                    json,
                                    root,
                                    output,
                                    python,
                                    "http://127.0.0.1:" + source.getAddress().getPort(),
                                    Duration.ofSeconds(5),
                                    Duration.ofMinutes(1)),
                            "cloud-source",
                            Duration.ofSeconds(10));
            for (int sample = 0; sample < 3; sample++) {
                scenario.set(sample);
                var submitted =
                        jobs.submit(
                                new IngestionRequest(
                                        tenant,
                                        account,
                                        ReportKind.CAMPAIGN,
                                        new DateRange(
                                                LocalDate.of(2026, 10, 1),
                                                LocalDate.of(2026, 10, 1)),
                                        "cloud-proof-" + sample));
                assertThat(local.pollOnce()).isTrue();
                long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                IngestionJob current = jobs.find(tenant, submitted.id()).orElseThrow();
                while (current.status() == IngestionStatus.RUNNING
                        && System.nanoTime() < deadline) {
                    cloud.pollOnce();
                    Thread.sleep(1000);
                    current = jobs.find(tenant, submitted.id()).orElseThrow();
                }
                assertThat(current.status())
                        .as(
                                "Cloud job %s stage %s error %s",
                                current.id(), current.stage(), current.errorCode())
                        .isEqualTo(IngestionStatus.SUCCEEDED);
                var totals =
                        bigquery.query(
                                        QueryJobConfiguration.newBuilder(
                                                        "SELECT COUNT(*) AS row_count, COALESCE(SUM(cost),0) AS cost,COALESCE(SUM(conversions),0) AS conversions FROM `"
                                                                + project
                                                                + "."
                                                                + dataset
                                                                + ".fact_campaign_daily` WHERE tenant_id=@tenant AND account_id=@account AND report_date=DATE '2026-10-01'")
                                                .setUseLegacySql(false)
                                                .setMaximumBytesBilled(cap)
                                                .addNamedParameter(
                                                        "tenant",
                                                        QueryParameterValue.string(
                                                                tenant.toString()))
                                                .addNamedParameter(
                                                        "account",
                                                        QueryParameterValue.string(
                                                                account.toString()))
                                                .build())
                                .iterateAll()
                                .iterator()
                                .next();
                assertThat(totals.get("row_count").getLongValue()).isEqualTo(sample == 2 ? 0 : 1);
                assertThat(totals.get("cost").getNumericValue())
                        .isEqualByComparingTo(sample == 2 ? "0" : "1.25");
                assertThat(totals.get("conversions").getNumericValue())
                        .isEqualByComparingTo(sample == 0 ? "0.5" : sample == 1 ? "1.5" : "0");
                if (sample == 0) {
                    gateway.submit(firstLoad.get());
                    assertThat(gateway.inspect(firstLoad.get()).state())
                            .isEqualTo(RemoteJobOutcome.State.SUCCEEDED);
                    var rows =
                            bigquery.query(
                                            QueryJobConfiguration.newBuilder(
                                                            "SELECT COUNT(*) FROM `"
                                                                    + project
                                                                    + "."
                                                                    + dataset
                                                                    + ".stg_campaign_daily` WHERE report_date=DATE '2026-10-01' AND run_id=@run")
                                                    .setUseLegacySql(false)
                                                    .setMaximumBytesBilled(cap)
                                                    .addNamedParameter(
                                                            "run",
                                                            QueryParameterValue.string(
                                                                    firstLoad
                                                                            .get()
                                                                            .snapshot()
                                                                            .runId()
                                                                            .toString()))
                                                    .build())
                                    .iterateAll()
                                    .iterator()
                                    .next();
                    assertThat(rows.get(0).getLongValue()).isEqualTo(1);
                }
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ingestion_checkpoints", Integer.class))
                    .isEqualTo(1);
        } finally {
            source.stop(0);
        }
    }

    private static String setting(Properties properties, String key) {
        String value = System.getenv().getOrDefault(key, properties.getProperty(key, ""));
        if (value.isBlank())
            throw new IllegalArgumentException(
                    "Configure "
                            + key
                            + " in .local/cloud.properties before running live cloud proof");
        return value;
    }
}
