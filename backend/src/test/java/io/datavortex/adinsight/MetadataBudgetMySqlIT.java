package io.datavortex.adinsight;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datavortex.adinsight.advertising.application.service.MetadataService;
import io.datavortex.adinsight.advertising.domain.model.LocalSettingsPatch;
import io.datavortex.adinsight.budgeting.application.service.BudgetService;
import io.datavortex.adinsight.budgeting.domain.model.*;
import io.datavortex.adinsight.identityaccess.domain.model.*;
import io.datavortex.adinsight.shared.domain.BusinessException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
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
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("local")
class MetadataBudgetMySqlIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate api;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MetadataService metadata;
    @Autowired BudgetService budgets;
    UUID tenant, other, account, campaign, group, keyword, user;
    TestRestTemplate client;
    HttpHeaders headers;
    TenantScope scope;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        other = UUID.randomUUID();
        account = UUID.randomUUID();
        campaign = UUID.randomUUID();
        group = UUID.randomUUID();
        keyword = UUID.randomUUID();
        user = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'A','ACTIVE'),(?,'B','ACTIVE')",
                tenant.toString(),
                other.toString());
        String email = user + "@test.example";
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,status) VALUES (?,?,?,'ACTIVE')",
                user.toString(),
                email,
                passwords.encode("test-only-password"));
        jdbc.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'TENANT_ADMIN'),(?,?,'VIEWER')",
                tenant.toString(),
                user.toString(),
                other.toString(),
                user.toString());
        jdbc.update(
                "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status) VALUES (?,?,'MOCK_REST','source','VND','UTC','ACTIVE')",
                account.toString(),
                tenant.toString());
        jdbc.update(
                "INSERT INTO campaigns(id,tenant_id,account_id,source_campaign_id,name,source_status,local_note,local_tags) VALUES (?,?,?,'001','Source name','ENABLED','Keep note','[\"old\"]')",
                campaign.toString(),
                tenant.toString(),
                account.toString());
        jdbc.update(
                "INSERT INTO ad_groups(id,tenant_id,campaign_id,source_ad_group_id,name,status) VALUES (?,?,?,'g1','Group','ENABLED')",
                group.toString(),
                tenant.toString(),
                campaign.toString());
        jdbc.update(
                "INSERT INTO keywords(id,tenant_id,campaign_id,ad_group_id,source_keyword_id,text,match_type,status,bid_micros) VALUES (?,?,?,?,'k1','Shoes','EXACT','ENABLED',9007199254740993)",
                keyword.toString(),
                tenant.toString(),
                campaign.toString(),
                group.toString());
        var cookieJar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookieJar).build();
        client =
                new TestRestTemplate(
                        new RestTemplateBuilder()
                                .rootUri(api.getRootUri())
                                .requestFactory(() -> new JdkClientHttpRequestFactory(http)),
                        email,
                        "test-only-password");
        headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenant.toString());
        var csrf =
                client.exchange(
                        "/api/v1/csrf", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers.set(
                csrf.getBody().get("headerName").toString(),
                csrf.getBody().get("token").toString());
        scope = new TenantScope(tenant, user.toString(), TenantRole.TENANT_ADMIN);
    }

    @Test
    void detailsAreTenantScopedAndDeletedParentsHideChildren() {
        var detail = call(HttpMethod.GET, "/campaigns/" + campaign, null);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getBody().path("localNote").asText()).isEqualTo("Keep note");
        var child = call(HttpMethod.GET, "/keywords/" + keyword, null);
        assertThat(child.getBody().path("bidMicros").asText()).isEqualTo("9007199254740993");
        headers.set("X-Tenant-Id", other.toString());
        assertThat(call(HttpMethod.GET, "/campaigns/" + campaign, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/keywords/" + keyword, null).getStatusCode().value())
                .isEqualTo(404);
        headers.set("X-Tenant-Id", tenant.toString());
        jdbc.update(
                "UPDATE memberships SET role='VIEWER' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(call(HttpMethod.GET, "/keywords/" + keyword, null).getStatusCode().value())
                .isEqualTo(200);
        jdbc.update("UPDATE ad_groups SET is_deleted=TRUE WHERE id=?", group.toString());
        assertThat(call(HttpMethod.GET, "/keywords/" + keyword, null).getStatusCode().value())
                .isEqualTo(404);
        jdbc.update("UPDATE ad_groups SET is_deleted=FALSE WHERE id=?", group.toString());
        jdbc.update("UPDATE campaigns SET is_deleted=TRUE WHERE id=?", campaign.toString());
        assertThat(call(HttpMethod.GET, "/campaigns/" + campaign, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/keywords/" + keyword, null).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void patchPreservesSourceFieldsAndCommitsImmutableFullProjectionAndAudit() throws Exception {
        var response =
                call(
                        HttpMethod.PATCH,
                        "/campaigns/" + campaign + "/local-settings",
                        Map.of("expectedVersion", 1, "localTags", List.of("local")));
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        JsonNode changed = response.getBody();
        assertThat(changed.path("searchVersion").asLong()).isEqualTo(2);
        assertThat(changed.path("name").asText()).isEqualTo("Source name");
        assertThat(changed.path("sourceStatus").asText()).isEqualTo("ENABLED");
        assertThat(changed.path("localNote").asText()).isEqualTo("Keep note");
        String payload =
                jdbc.queryForObject(
                        "SELECT payload FROM outbox_events WHERE aggregate_id=?",
                        String.class,
                        campaign.toString());
        JsonNode event = json.readTree(payload);
        assertThat(event.path("document_id").asText()).isEqualTo(tenant + ":" + campaign);
        assertThat(event.path("title").asText()).isEqualTo("Source name");
        assertThat(event.path("autocomplete").asText()).isEqualTo("Source name");
        assertThat(event.path("tags").get(0).asText()).isEqualTo("local");
        assertThat(event.path("updated_at").asText()).isEqualTo(changed.path("updatedAt").asText());
        assertThat(event.path("search_version").asLong()).isEqualTo(2);
        assertThat(count("audit_logs", "entity_id", campaign)).isEqualTo(1);
        var noop =
                call(
                        HttpMethod.PATCH,
                        "/campaigns/" + campaign + "/local-settings",
                        Map.of("expectedVersion", 2, "localTags", List.of("local")));
        assertThat(noop.getBody().path("searchVersion").asLong()).isEqualTo(2);
        var clear = new LinkedHashMap<String, Object>();
        clear.put("expectedVersion", 2);
        clear.put("localNote", null);
        assertThat(
                        call(HttpMethod.PATCH, "/campaigns/" + campaign + "/local-settings", clear)
                                .getBody()
                                .path("localNote")
                                .isNull())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM outbox_events WHERE aggregate_id=? AND aggregate_version=2",
                                String.class,
                                campaign.toString()))
                .isEqualTo(payload);
        assertThat(
                        call(
                                        HttpMethod.PATCH,
                                        "/campaigns/" + campaign + "/local-settings",
                                        Map.of("expectedVersion", 1, "localNote", "stale"))
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        call(
                                        HttpMethod.PATCH,
                                        "/campaigns/" + campaign + "/local-settings",
                                        Map.of("expectedVersion", 3))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        call(
                                        HttpMethod.PATCH,
                                        "/campaigns/" + campaign + "/local-settings",
                                        Map.of("expectedVersion", 3, "localTags", List.of(12)))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
    }

    @Test
    void concurrentPatchHasExactlyOneWinner() throws Exception {
        var results =
                race(
                        () ->
                                metadata.patch(
                                        scope,
                                        campaign,
                                        new LocalSettingsPatch(1, true, "A", false, List.of()),
                                        UUID.randomUUID()),
                        () ->
                                metadata.patch(
                                        scope,
                                        campaign,
                                        new LocalSettingsPatch(1, true, "B", false, List.of()),
                                        UUID.randomUUID()));
        assertThat(results).containsExactlyInAnyOrder("OK", "OPTIMISTIC_CONFLICT");
        assertThat(count("outbox_events", "aggregate_id", campaign)).isEqualTo(1);
        assertThat(count("audit_logs", "entity_id", campaign)).isEqualTo(1);
    }

    @Test
    void metadataAndOutboxRollBackWhenAuditFails() {
        jdbc.execute(
                "ALTER TABLE audit_logs ADD CONSTRAINT metadata_audit_fault CHECK (tenant_id <> '"
                        + tenant
                        + "' OR action <> 'CAMPAIGN_LOCAL_SETTINGS_UPDATED')");
        try {
            assertThatThrownBy(
                            () ->
                                    metadata.patch(
                                            scope,
                                            campaign,
                                            new LocalSettingsPatch(
                                                    1, true, "must rollback", false, List.of()),
                                            UUID.randomUUID()))
                    .hasRootCauseMessage("Check constraint 'metadata_audit_fault' is violated.");
            var unchanged = metadata.campaign(scope, campaign);
            assertThat(unchanged.searchVersion()).isEqualTo(1);
            assertThat(unchanged.localNote()).isEqualTo("Keep note");
            assertThat(count("outbox_events", "aggregate_id", campaign)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_logs DROP CHECK metadata_audit_fault");
        }
    }

    @Test
    void accountCreationIsAdminOnlyScopedAndNeverDisclosesCredentialReference() {
        String secret = "projects/dev-project/secrets/provider/versions/latest";
        var body =
                Map.of(
                        "platform",
                        "MOCK_REST",
                        "sourceAccountId",
                        "new-source",
                        "currency",
                        "VND",
                        "timezone",
                        "Asia/Ho_Chi_Minh",
                        "credentialRef",
                        secret);
        var created = call(HttpMethod.POST, "/accounts", body);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        UUID id = UUID.fromString(created.getBody().path("account").path("id").asText());
        assertThat(created.getBody().toString()).doesNotContain("credentialRef", secret);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT credential_ref FROM ad_accounts WHERE id=?",
                                String.class,
                                id.toString()))
                .isEqualTo(secret);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT after_data FROM audit_logs WHERE entity_id=?",
                                String.class,
                                id.toString()))
                .doesNotContain(secret, "credentialRef");
        assertThat(call(HttpMethod.POST, "/accounts", body).getStatusCode().value()).isEqualTo(409);
        assertThat(
                        call(
                                        HttpMethod.POST,
                                        "/accounts",
                                        Map.of(
                                                "platform",
                                                "MOCK_REST",
                                                "sourceAccountId",
                                                "bad",
                                                "currency",
                                                "VND",
                                                "timezone",
                                                "UTC",
                                                "credentialRef",
                                                "literal-provider-token"))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        jdbc.update(
                "UPDATE memberships SET role='ANALYST' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(call(HttpMethod.POST, "/accounts", body).getStatusCode().value()).isEqualTo(403);
        assertThat(
                        call(
                                        HttpMethod.PATCH,
                                        "/campaigns/" + campaign + "/local-settings",
                                        Map.of("expectedVersion", 1, "localNote", "denied"))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        assertThat(
                        call(
                                        HttpMethod.PUT,
                                        "/campaigns/" + campaign + "/budget-plan",
                                        budgetBody(0))
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void budgetHttpPreservesDecimalsAndRevisionsAndRejectsInvalidCurrencyOrStaleWrites() {
        var body = budgetBody(0);
        var created = call(HttpMethod.PUT, "/campaigns/" + campaign + "/budget-plan", body);
        assertThat(created.getStatusCode().value()).as("%s", created.getBody()).isEqualTo(200);
        assertThat(created.getBody().path("amount").asText()).isEqualTo("1000000.123456");
        assertThat(created.getBody().path("kpiTargets").get(0).path("targetValue").asText())
                .isEqualTo("2.123456");
        assertThat(created.getBody().path("revision").asLong()).isEqualTo(1);
        assertThat(
                        call(HttpMethod.PUT, "/campaigns/" + campaign + "/budget-plan", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        body.put("expectedRevision", 1);
        body.put("currency", "USD");
        assertThat(
                        call(HttpMethod.PUT, "/campaigns/" + campaign + "/budget-plan", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        body.put("currency", "VND");
        body.put("amount", "0.0000001");
        assertThat(
                        call(HttpMethod.PUT, "/campaigns/" + campaign + "/budget-plan", body)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        body.put("amount", "2000000.000001");
        assertThat(
                        call(HttpMethod.PUT, "/campaigns/" + campaign + "/budget-plan", body)
                                .getBody()
                                .path("revision")
                                .asLong())
                .isEqualTo(2);
        String query =
                "/campaigns/"
                        + campaign
                        + "/budget-plan?periodStart=2026-10-01&periodEnd=2026-10-31";
        jdbc.update(
                "UPDATE memberships SET role='VIEWER' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(call(HttpMethod.GET, query, null).getBody().path("revision").asLong())
                .isEqualTo(2);
        headers.set("X-Tenant-Id", other.toString());
        assertThat(call(HttpMethod.GET, query, null).getStatusCode().value()).isEqualTo(404);
        headers.set("X-Tenant-Id", tenant.toString());
        jdbc.update("UPDATE campaigns SET is_deleted=TRUE WHERE id=?", campaign.toString());
        assertThat(call(HttpMethod.GET, query, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void overlappingBudgetsAreSerializedAndInactivePlansDoNotBlock() throws Exception {
        var results =
                race(
                        () ->
                                budgets.save(
                                        scope,
                                        campaign,
                                        change("2026-10-01", "2026-10-31", 0, true),
                                        UUID.randomUUID()),
                        () ->
                                budgets.save(
                                        scope,
                                        campaign,
                                        change("2026-10-15", "2026-11-15", 0, true),
                                        UUID.randomUUID()));
        assertThat(results).containsExactlyInAnyOrder("OK", "BUDGET_PERIOD_CONFLICT");
        var row =
                jdbc.queryForMap(
                        "SELECT period_start,period_end FROM budget_plans WHERE campaign_id=?",
                        campaign.toString());
        LocalDate start = ((java.sql.Date) row.get("period_start")).toLocalDate();
        LocalDate end = ((java.sql.Date) row.get("period_end")).toLocalDate();
        var inactive =
                budgets.save(
                        scope,
                        campaign,
                        change(start.toString(), end.toString(), 1, false),
                        UUID.randomUUID());
        assertThat(inactive.revision()).isEqualTo(2);
        budgets.save(
                scope, campaign, change("2026-10-02", "2026-11-01", 0, true), UUID.randomUUID());
        // Inclusive end: the following day is adjacent rather than overlapping.
        budgets.save(
                scope, campaign, change("2026-11-02", "2026-11-03", 0, true), UUID.randomUUID());
    }

    @Test
    void budgetTargetsAuditAndRevisionRollBackTogetherAndTenantForeignKeysHold() {
        var first =
                budgets.save(
                        scope,
                        campaign,
                        change("2026-10-01", "2026-10-31", 0, true),
                        UUID.randomUUID());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO kpi_targets(id,tenant_id,budget_plan_id,metric,operator,target_value,min_samples) VALUES (?,?,?,'ROAS','GTE',2,0)",
                                        UUID.randomUUID().toString(),
                                        other.toString(),
                                        first.id().toString()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.execute(
                "ALTER TABLE audit_logs ADD CONSTRAINT budget_audit_fault CHECK (tenant_id <> '"
                        + tenant
                        + "' OR action <> 'BUDGET_SAVED' OR before_data IS NULL)");
        try {
            assertThatThrownBy(
                            () ->
                                    budgets.save(
                                            scope,
                                            campaign,
                                            change("2026-10-01", "2026-10-31", 1, false),
                                            UUID.randomUUID()))
                    .hasRootCauseMessage("Check constraint 'budget_audit_fault' is violated.");
            var persisted =
                    budgets.get(
                            scope,
                            campaign,
                            LocalDate.parse("2026-10-01"),
                            LocalDate.parse("2026-10-31"));
            assertThat(persisted.revision()).isEqualTo(1);
            assertThat(persisted.active()).isTrue();
            assertThat(persisted.kpiTargets()).hasSize(1);
            assertThat(count("audit_logs", "entity_id", first.id())).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE audit_logs DROP CHECK budget_audit_fault");
        }
    }

    @Test
    void cloudAnalyticsDoesNotFallBackToLocalOrReturnFabricatedEmptyData() {
        var response =
                call(
                        HttpMethod.GET,
                        "/analytics/summary?dateFrom=2026-10-01&dateTo=2026-10-02",
                        null);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().path("code").asText()).isEqualTo("WAREHOUSE_UNAVAILABLE");
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body) {
        return client.exchange(
                "/api/v1" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private long count(String table, String field, UUID id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + field + "=?",
                Long.class,
                id.toString());
    }

    private Map<String, Object> budgetBody(long revision) {
        return new HashMap<>(
                Map.of(
                        "periodStart",
                        "2026-10-01",
                        "periodEnd",
                        "2026-10-31",
                        "amount",
                        "1000000.123456",
                        "currency",
                        "VND",
                        "expectedRevision",
                        revision,
                        "active",
                        true,
                        "kpiTargets",
                        List.of(
                                Map.of(
                                        "metric",
                                        "ROAS",
                                        "operator",
                                        "GTE",
                                        "targetValue",
                                        "2.123456",
                                        "minSamples",
                                        0))));
    }

    private BudgetChange change(String start, String end, long revision, boolean active) {
        return new BudgetChange(
                LocalDate.parse(start),
                LocalDate.parse(end),
                new BigDecimal("1000000.123456"),
                Currency.getInstance("VND"),
                revision,
                active,
                List.of(
                        new KpiTarget(
                                KpiMetric.ROAS, KpiOperator.GTE, new BigDecimal("2.123456"), 0)));
    }

    private List<String> race(Callable<?> a, Callable<?> b) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            List<Future<String>> pending = new ArrayList<>();
            for (var task : List.of(a, b))
                pending.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await();
                                    try {
                                        task.call();
                                        return "OK";
                                    } catch (BusinessException error) {
                                        return error.code().name();
                                    }
                                }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(
                    pending.get(0).get(20, TimeUnit.SECONDS),
                    pending.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
