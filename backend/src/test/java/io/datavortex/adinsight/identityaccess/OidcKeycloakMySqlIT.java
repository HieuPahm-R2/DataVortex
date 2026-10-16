package io.datavortex.adinsight.identityaccess;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jwt.JWTParser;
import io.datavortex.adinsight.identityaccess.application.port.IExternalIdentityDirectory;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Keycloak discovery, signing and JWKS; never submits external cloud work. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OidcKeycloakMySqlIT {
    private static final String SECRET = UUID.randomUUID().toString();
    private static final String USER_PASSWORD = UUID.randomUUID().toString();
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8");

    @Container
    static final GenericContainer<?> KEYCLOAK =
            new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
                    .withExposedPorts(8080)
                    .withEnv("JAVA_OPTS_KC_HEAP", "-Xms128m -Xmx384m")
                    .withCopyToContainer(
                            Transferable.of(testRealm()),
                            "/opt/keycloak/data/import/adinsight-realm.json")
                    .withCommand("start-dev", "--import-realm")
                    .waitingFor(Wait.forHttp("/realms/adinsight/.well-known/openid-configuration"))
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static byte[] testRealm() {
        try {
            var json = new ObjectMapper();
            ObjectNode realm =
                    (ObjectNode)
                            json.readTree(
                                    Files.readString(
                                            Path.of("../infra/keycloak/adinsight-realm.json")));
            ObjectNode client = ((ArrayNode) realm.get("clients")).addObject();
            client.put("clientId", "integration-only")
                    .put("protocol", "openid-connect")
                    .put("enabled", true)
                    .put("publicClient", false)
                    .put("secret", SECRET)
                    .put("serviceAccountsEnabled", true)
                    .put("standardFlowEnabled", false)
                    .put("directAccessGrantsEnabled", false);
            client.putArray("protocolMappers")
                    .addObject()
                    .put("name", "api-audience")
                    .put("protocol", "openid-connect")
                    .put("protocolMapper", "oidc-audience-mapper")
                    .putObject("config")
                    .put("included.client.audience", "adinsight-api")
                    .put("access.token.claim", "true")
                    .put("id.token.claim", "false");
            realm.putArray("users")
                    .addObject()
                    .put("username", "oidc-proof")
                    .put("enabled", true)
                    .put("firstName", "OIDC")
                    .put("lastName", "Proof")
                    .put("email", "oidc-proof@example.test")
                    .put("emailVerified", true)
                    .putArray("credentials")
                    .addObject()
                    .put("type", "password")
                    .put("value", USER_PASSWORD)
                    .put("temporary", false);
            return json.writeValueAsBytes(realm);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String issuer() {
        return "http://"
                + KEYCLOAK.getHost()
                + ":"
                + KEYCLOAK.getMappedPort(8080)
                + "/realms/adinsight";
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("adinsight.security.oidc.issuer-uri", OidcKeycloakMySqlIT::issuer);
        properties.add("adinsight.security.oidc.audience", () -> "adinsight-api");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate api;
    @Autowired IExternalIdentityDirectory identities;
    UUID tenant, otherTenant, account, user;
    String token, subject;

    @BeforeEach
    void provisionExplicitLocalIdentity() throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "grant_type=client_credentials&client_id=integration-only&client_secret="
                                                + SECRET))
                        .build();
        var issued = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(issued.statusCode()).isEqualTo(200);
        token = new ObjectMapper().readTree(issued.body()).get("access_token").asText();
        subject = JWTParser.parse(token).getJWTClaimsSet().getSubject();
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        account = UUID.randomUUID();
        user = UUID.randomUUID();
        jdbc.update("DELETE FROM user_identities");
        jdbc.update(
                "INSERT INTO tenants(id,name,status) VALUES (?,'A','ACTIVE'),(?,'B','ACTIVE')",
                tenant.toString(),
                otherTenant.toString());
        jdbc.update(
                "INSERT INTO users(id,email,status) VALUES (?,?,'ACTIVE')",
                user.toString(),
                user + "@example.test");
        jdbc.update(
                "INSERT INTO user_identities(issuer,subject,user_id) VALUES (?,?,?)",
                issuer(),
                subject,
                user.toString());
        jdbc.update(
                "INSERT INTO memberships(tenant_id,user_id,role) VALUES (?,?,'VIEWER')",
                tenant.toString(),
                user.toString());
        jdbc.update(
                "INSERT INTO ad_accounts(id,tenant_id,platform,source_account_id,currency,timezone,status,credential_ref) VALUES (?,?,'MOCK_REST','001','VND','UTC','ACTIVE','secret-reference')",
                account.toString(),
                tenant.toString());
    }

    @Test
    void realTokenReadsOwnedMetadataAndDbRoleControlsMutationWithoutCsrfSession() {
        var own = exchange("/api/v1/accounts", tenant, HttpMethod.GET, null);
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getBody())
                .contains(account.toString())
                .doesNotContain("secret-reference", "credentialRef");
        assertThat(own.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(
                        exchange("/api/v1/accounts", otherTenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        var payload =
                Map.of(
                        "accountId",
                        account,
                        "reportKind",
                        "CAMPAIGN",
                        "dateFrom",
                        "2026-10-01",
                        "dateTo",
                        "2026-10-02");
        assertThat(
                        exchange("/api/v1/ingestion-jobs", tenant, HttpMethod.POST, payload)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        jdbc.update(
                "UPDATE memberships SET role='TENANT_ADMIN' WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        var created = exchange("/api/v1/ingestion-jobs", tenant, HttpMethod.POST, payload);
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(created.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(
                        exchange(
                                        created.getHeaders().getLocation().toString(),
                                        otherTenant,
                                        HttpMethod.GET,
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void revocationAndInactiveAccountsAreEffectiveForAnAlreadyIssuedToken() {
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        jdbc.update("UPDATE tenants SET status='INACTIVE' WHERE id=?", tenant.toString());
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        jdbc.update("UPDATE tenants SET status='ACTIVE' WHERE id=?", tenant.toString());
        jdbc.update(
                "DELETE FROM memberships WHERE tenant_id=? AND user_id=?",
                tenant.toString(),
                user.toString());
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", user.toString());
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", user.toString());
        jdbc.update("DELETE FROM user_identities WHERE user_id=?", user.toString());
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void identityMappingIsExactAndUniqueAndBasicIsUnavailable() {
        assertThat(identities.activeUserId(issuer(), subject)).contains(user.toString());
        assertThat(identities.activeUserId(issuer() + "/", subject)).isEmpty();
        jdbc.update(
                "INSERT INTO user_identities(issuer,subject,user_id) VALUES (?,?,?)",
                issuer(),
                "CaseSensitive",
                user.toString());
        assertThat(identities.activeUserId(issuer(), "casesensitive")).isEmpty();
        assertThat(identities.activeUserId(issuer(), subject + " ")).isEmpty();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO user_identities(issuer,subject,user_id) VALUES (?,?,?)",
                                        issuer(),
                                        subject,
                                        user.toString()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(
                        api.withBasicAuth(user + "@example.test", "unused")
                                .getForEntity("/api/v1/accounts", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void importedWebClientUsesAuthorizationCodePkceAndIdTokensCannotCallTheApi() throws Exception {
        String redirect = "http://localhost:5173/auth/callback";
        String verifier = UUID.randomUUID().toString() + UUID.randomUUID();
        String challenge =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize =
                issuer()
                        + "/protocol/openid-connect/auth?client_id=adinsight-web&response_type=code&scope=openid&state=proof&redirect_uri="
                        + encoded(redirect);
        var browser = HttpClient.newHttpClient();
        var noPkce =
                browser.send(
                        HttpRequest.newBuilder(URI.create(authorize)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(noPkce.statusCode()).isEqualTo(302);
        String pkceError =
                URLDecoder.decode(
                        noPkce.headers().firstValue("Location").orElseThrow(),
                        StandardCharsets.UTF_8);
        assertThat(pkceError).contains("error=invalid_request", "code_challenge");
        var login =
                browser.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                authorize
                                                        + "&code_challenge_method=S256&code_challenge="
                                                        + challenge))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        var cookies =
                login.headers().allValues("Set-Cookie").stream()
                        .flatMap(value -> java.net.HttpCookie.parse(value).stream())
                        .toList();
        assertThat(cookies)
                .extracting(java.net.HttpCookie::getName)
                .contains("AUTH_SESSION_ID", "KC_RESTART");
        // Replay the local browser's cookies to the same Keycloak origin. Java's cookie
        // handler does not model browser localhost/secure-cookie behavior.
        String cookieHeader =
                cookies.stream()
                        .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                        .collect(java.util.stream.Collectors.joining("; "));
        var form =
                Pattern.compile("<form(?=[^>]*id=\"kc-form-login\")[^>]*action=\"([^\"]+)\"")
                        .matcher(login.body());
        assertThat(form.find())
                .as("Keycloak authorization-code login form must be available")
                .isTrue();
        String action = form.group(1).replace("&amp;", "&");
        var signedIn =
                browser.send(
                        HttpRequest.newBuilder(URI.create(action))
                                .header("Cookie", cookieHeader)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "username=oidc-proof&password="
                                                        + encoded(USER_PASSWORD)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        var feedback =
                Pattern.compile("<(?:title|span|p|h1)[^>]*>([^<]+)</(?:title|span|p|h1)>")
                        .matcher(signedIn.body());
        var messages = new java.util.ArrayList<String>();
        while (feedback.find()) {
            messages.add(feedback.group(1).strip());
        }
        assertThat(signedIn.statusCode())
                .as("Keycloak login feedback: %s", messages)
                .isEqualTo(302);
        var callback = URI.create(signedIn.headers().firstValue("Location").orElseThrow());
        var parameters =
                java.util.Arrays.stream(callback.getRawQuery().split("&"))
                        .map(pair -> pair.split("=", 2))
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        pair -> pair[0],
                                        pair ->
                                                URLDecoder.decode(
                                                        pair[1], StandardCharsets.UTF_8)));
        assertThat(parameters.get("state")).isEqualTo("proof");
        var issued =
                browser.send(
                        HttpRequest.newBuilder(
                                        URI.create(issuer() + "/protocol/openid-connect/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "grant_type=authorization_code&client_id=adinsight-web&redirect_uri="
                                                        + encoded(redirect)
                                                        + "&code="
                                                        + encoded(parameters.get("code"))
                                                        + "&code_verifier="
                                                        + encoded(verifier)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(issued.statusCode()).isEqualTo(200);
        var credentials = new ObjectMapper().readTree(issued.body());
        token = credentials.get("access_token").asText();
        String humanSubject = JWTParser.parse(token).getJWTClaimsSet().getSubject();
        jdbc.update(
                "INSERT INTO user_identities(issuer,subject,user_id) VALUES (?,?,?)",
                issuer(),
                humanSubject,
                user.toString());
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        token = credentials.get("id_token").asText();
        assertThat(
                        exchange("/api/v1/accounts", tenant, HttpMethod.GET, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> exchange(
            String path, UUID selectedTenant, HttpMethod method, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Tenant-Id", selectedTenant.toString());
        headers.set("Idempotency-Key", "keycloak-proof");
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return api.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
