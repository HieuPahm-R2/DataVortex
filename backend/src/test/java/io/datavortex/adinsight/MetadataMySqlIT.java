package io.datavortex.adinsight;

import static org.assertj.core.api.Assertions.*;

import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.infrastructure.security.DatabaseUserDetailsService;
import io.datavortex.adinsight.shared.domain.PageQuery;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
class MetadataMySqlIT {
    @Test
    void accountPaginationIsBoundedStableAndTenantScoped() {
        String tenant = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'Pages','ACTIVE'),(?,'Other','ACTIVE')",
                tenant,
                other);
        String[] ids = {
            "10000000-0000-0000-0000-000000000003",
            "10000000-0000-0000-0000-000000000001",
            "10000000-0000-0000-0000-000000000002"
        };
        for (String id : ids) {
            jdbc.update(
                    "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status) VALUES (?,?,'MOCK_REST',?,'VND','UTC','ACTIVE')",
                    id,
                    tenant,
                    id);
        }
        jdbc.update(
                "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status) VALUES ('00000000-0000-0000-0000-000000000001',?,'MOCK_REST','other','VND','UTC','ACTIVE')",
                other);
        var first = accounts.list(UUID.fromString(tenant), new PageQuery(0, 2));
        assertThat(first.items())
                .extracting(value -> value.id().toString())
                .containsExactly(ids[1], ids[2]);
        assertThat(first.hasMore()).isTrue();
        var second = accounts.list(UUID.fromString(tenant), new PageQuery(1, 2));
        assertThat(second.items())
                .extracting(value -> value.id().toString())
                .containsExactly(ids[0]);
        assertThat(second.hasMore()).isFalse();
        assertThat(accounts.list(UUID.fromString(tenant), new PageQuery(2, 2)).items()).isEmpty();
    }

    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired IAccountCatalog accounts;
    @Autowired IMembershipDirectory memberships;
    @Autowired DatabaseUserDetailsService users;
    @Autowired PasswordEncoder passwords;
    @Autowired TestRestTemplate api;

    @Test
    void migrationEnforcesTenantParentAndOutboxVersionUniqueness() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String account = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?, 'A','ACTIVE'), (?, 'B','ACTIVE')",
                a,
                b);
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,status) VALUES (?, 'a@example.test',?,'ACTIVE')",
                user,
                passwords.encode("local-integration-only"));
        jdbc.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'VIEWER')", a, user);
        jdbc.update(
                """
                                                INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status)
                                                VALUES (?,?,'MOCK_REST','001','VND','Asia/Ho_Chi_Minh','ACTIVE')
                                                """,
                account,
                a);
        assertThat(accounts.list(UUID.fromString(a), new PageQuery(0, 20)).items()).hasSize(1);
        assertThat(accounts.list(UUID.fromString(b), new PageQuery(0, 20)).items()).isEmpty();
        assertThat(memberships.role(UUID.fromString(a), user)).contains(TenantRole.VIEWER);
        assertThat(memberships.role(UUID.fromString(b), user)).isEmpty();
        assertThat(users.loadUserByUsername("a@example.test").getUsername()).isEqualTo(user);
        var headers = new HttpHeaders();
        headers.set("X-Tenant-Id", a);
        var response =
                api.withBasicAuth("a@example.test", "local-integration-only")
                        .exchange(
                                "/api/v1/accounts",
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains(account).doesNotContain("credentialRef");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        headers.set("X-Tenant-Id", b);
        var forbidden =
                api.withBasicAuth("a@example.test", "local-integration-only")
                        .exchange(
                                "/api/v1/accounts",
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getBody()).contains("TENANT_FORBIDDEN");
        String campaign = UUID.randomUUID().toString();
        String insert =
                """
                                INSERT INTO campaigns(id,tenant_id,account_id,source_campaign_id,name,source_status,local_tags)
                                VALUES (?,?,?,'source-001','Shoes','ENABLED','[]')
                                """;
        assertThatThrownBy(() -> jdbc.update(insert, campaign, b, account))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update(insert, campaign, a, account);
        String outbox =
                """
                                INSERT INTO outbox_events(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,
                                event_type,schema_version,payload) VALUES (?,?,'CAMPAIGN',?,1,'UPSERT',1,'{}')
                                """;
        jdbc.update(outbox, UUID.randomUUID().toString(), a, campaign);
        assertThatThrownBy(() -> jdbc.update(outbox, UUID.randomUUID().toString(), a, campaign))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
