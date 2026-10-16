package io.datavortex.adinsight.analytics;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.datavortex.adinsight.analytics.infrastructure.config.AnalyticsConfiguration;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles({"local", "dev-warehouse"})
class LocalAnalyticsMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");
    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final Path PYTHON =
            ROOT.resolve(".venv")
                    .resolve(
                            System.getProperty("os.name").startsWith("Windows")
                                    ? "Scripts/python.exe"
                                    : "bin/python");
    static final Path OUTPUT = prepare();

    static Path prepare() {
        try {
            // Keep fixture paths short enough for Windows raw platform/tenant/account/run paths.
            Files.createDirectories(ROOT.resolve(".local"));
            Path owned =
                    ROOT.resolve(".local")
                            .resolve("a" + UUID.randomUUID().toString().substring(0, 8));
            Files.createDirectory(owned);
            Path output = owned.resolve("d");
            Process process =
                    new ProcessBuilder(
                                    PYTHON.toString(),
                                    "-m",
                                    "scripts.prepare_local_backend",
                                    "--output",
                                    output.toString())
                            .directory(ROOT.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(owned.resolve("prepare.log").toFile())
                            .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Local fixture process timeout");
            }
            if (process.exitValue() != 0)
                throw new IllegalStateException(
                        "Local fixture process failed; inspect " + owned.resolve("prepare.log"));
            return output;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot prepare isolated local warehouse", error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", MYSQL::getJdbcUrl);
        props.add("spring.datasource.username", MYSQL::getUsername);
        props.add("spring.datasource.password", MYSQL::getPassword);
        props.add("analytics.local.root", () -> ROOT.toString());
        props.add("analytics.local.database", () -> OUTPUT.resolve("warehouse.sqlite").toString());
        props.add("analytics.local.python", () -> PYTHON.toString());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate api;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    static boolean seeded;
    String tenant, other, campaign, email;
    TestRestTemplate client;
    HttpHeaders headers;

    @BeforeEach
    void seed() throws Exception {
        if (!seeded) {
            jdbc.execute(
                    (ConnectionCallback<Void>)
                            connection -> {
                                ScriptUtils.executeSqlScript(
                                        connection,
                                        new FileSystemResource(OUTPUT.resolve("metadata.sql")));
                                return null;
                            });
            seeded = true;
        }
        var setup = json.readTree(OUTPUT.resolve("setup.json").toFile());
        tenant = setup.path("tenantIds").get(0).asText();
        other = setup.path("tenantIds").get(1).asText();
        campaign = setup.path("campaignId").asText();
        jdbc.update("UPDATE campaigns SET is_deleted=FALSE");
        String user = UUID.randomUUID().toString();
        email = user + "@analytics.test";
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,status) VALUES (?,?,?,'ACTIVE')",
                user,
                email,
                passwords.encode("local-proof-password"));
        jdbc.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'VIEWER'),(?,?,'VIEWER')",
                tenant,
                user,
                other,
                user);
        client = api.withBasicAuth(email, "local-proof-password");
        headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenant);
    }

    String range() {
        return "?dateFrom=2026-08-01&dateTo=2026-12-31";
    }

    ResponseEntity<JsonNode> get(String path) {
        return client.exchange(
                "/api/v1/analytics" + path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);
    }

    @Test
    void realHttpReadsGeneratedWarehouseAndKeepsCurrenciesSeparate() {
        var response = get("/summary" + range());
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        var body = response.getBody();
        assertThat(body.path("source").asText()).isEqualTo("LOCAL");
        assertThat(body.path("inputWatermark")).hasSize(2);
        assertThat(body.path("dataAsOf").isTextual()).isTrue();
        assertThat(body.path("groups")).hasSize(2);
        assertThat(body.path("groups").get(0).path("currency").asText()).isEqualTo("USD");
        var totals = body.path("groups").get(1).path("totals");
        assertThat(totals.path("cost").isTextual()).isTrue();
        assertThat(new BigDecimal(totals.path("cost").asText())).isEqualByComparingTo("488225000");
        assertThat(new BigDecimal(totals.path("conversions").asText()))
                .isEqualByComparingTo("1952.9");
        var filtered = get("/summary" + range() + "&currency=VND");
        assertThat(filtered.getBody().path("groups")).hasSize(1);
        assertThat(filtered.getBody().path("inputWatermark")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication_watermarks", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_checkpoints", Long.class))
                .isZero();
    }

    @Test
    void dailyMatchesCampaignTotalsAndRespectsDateAndDeletedParent() {
        var daily = get("/campaigns/" + campaign + "/daily" + range());
        assertThat(daily.getStatusCode().value()).isEqualTo(200);
        var group = daily.getBody().path("groups").get(0);
        assertThat(group.path("days")).hasSize(60);
        BigDecimal cost = BigDecimal.ZERO, conversions = BigDecimal.ZERO;
        for (var day : group.path("days")) {
            cost = cost.add(new BigDecimal(day.path("metrics").path("cost").asText()));
            conversions =
                    conversions.add(
                            new BigDecimal(day.path("metrics").path("conversions").asText()));
        }
        assertThat(cost).isEqualByComparingTo(group.path("totals").path("cost").asText());
        assertThat(conversions)
                .isEqualByComparingTo(group.path("totals").path("conversions").asText());
        assertThat(
                        get("/campaigns/"
                                        + campaign
                                        + "/daily?dateFrom=2026-09-01&dateTo=2026-09-01")
                                .getBody()
                                .path("groups")
                                .get(0)
                                .path("days"))
                .hasSize(1);
        headers.set("X-Tenant-Id", other);
        assertThat(get("/campaigns/" + campaign + "/daily" + range()).getStatusCode().value())
                .isEqualTo(404);
        headers.set("X-Tenant-Id", tenant);
        jdbc.update("UPDATE campaigns SET is_deleted=TRUE WHERE id=?", campaign);
        assertThat(get("/campaigns/" + campaign + "/daily" + range()).getStatusCode().value())
                .isEqualTo(404);
        var summary = get("/summary" + range() + "&currency=VND");
        assertThat(
                        new BigDecimal(
                                summary.getBody()
                                        .path("groups")
                                        .get(0)
                                        .path("totals")
                                        .path("cost")
                                        .asText()))
                .isLessThan(new BigDecimal("488225000"));
    }

    @Test
    void missingWarehouseIs503AndValidEmptyResultUsesZeroAndNullRatios() throws Exception {
        var empty = get("/summary?dateFrom=2025-01-01&dateTo=2025-01-01&currency=VND");
        assertThat(empty.getStatusCode().value()).isEqualTo(200);
        var totals = empty.getBody().path("groups").get(0).path("totals");
        assertThat(totals.path("cost").asText()).isEqualTo("0");
        assertThat(totals.path("cpa").isNull()).isTrue();
        Path database = OUTPUT.resolve("warehouse.sqlite"),
                saved = OUTPUT.resolve("warehouse.saved");
        Files.move(database, saved);
        try {
            var unavailable = get("/summary" + range());
            assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
            assertThat(unavailable.getBody().path("code").asText())
                    .isEqualTo("WAREHOUSE_UNAVAILABLE");
            assertThat(unavailable.getBody().toString())
                    .doesNotContain(OUTPUT.toString(), "sqlite3", "Traceback");
            assertThat(Files.exists(database)).isFalse();
        } finally {
            Files.move(saved, database);
        }
    }

    @Test
    void invalidDatesCurrencyAndForeignAccountAreRejected() {
        assertThat(get("/summary?dateFrom=2026-12-31&dateTo=2026-01-01").getStatusCode().value())
                .isEqualTo(400);
        assertThat(get("/summary?dateFrom=2025-01-01&dateTo=2026-12-31").getStatusCode().value())
                .isEqualTo(400);
        assertThat(get("/summary" + range() + "&currency=xxx").getStatusCode().value())
                .isEqualTo(400);
        assertThat(
                        get("/summary" + range() + "&accountId=" + UUID.randomUUID())
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void localWarehouseCannotBeEnabledWithoutExplicitDevelopmentProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("test", Map.of("analytics.mode", "local")));
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(
                    io.datavortex.adinsight.analytics.application.port.IAnalyticsScope.class,
                    () -> (tenant, query) -> List.of());
            context.register(AnalyticsConfiguration.class);
            assertThatThrownBy(context::refresh)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("dev-warehouse");
        }
    }
}
