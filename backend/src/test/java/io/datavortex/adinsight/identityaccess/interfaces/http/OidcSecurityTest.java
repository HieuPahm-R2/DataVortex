package io.datavortex.adinsight.identityaccess.interfaces.http;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import io.datavortex.adinsight.advertising.application.port.IAccountCatalog;
import io.datavortex.adinsight.advertising.domain.model.AccountPage;
import io.datavortex.adinsight.advertising.infrastructure.config.AdvertisingConfiguration;
import io.datavortex.adinsight.advertising.interfaces.http.controller.AccountController;
import io.datavortex.adinsight.identityaccess.application.port.IExternalIdentityDirectory;
import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import io.datavortex.adinsight.identityaccess.infrastructure.config.OidcSecurityConfiguration;
import io.datavortex.adinsight.shared.domain.PageQuery;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@ActiveProfiles("oidc")
@Import({OidcSecurityConfiguration.class, ErrorWriter.class, AdvertisingConfiguration.class})
class OidcSecurityTest {
    private static final String ISSUER = "https://keycloak.example.test/realms/adinsight";
    private static final String SUBJECT = "keycloak-subject";
    private static final UUID TENANT = UUID.randomUUID();
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    private static final AtomicBoolean KEYS_UNAVAILABLE = new AtomicBoolean();

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("proof").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    exchange -> {
                        byte[] body =
                                new JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(
                                KEYS_UNAVAILABLE.get() ? 503 : 200, body.length);
                        try (var output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    });
            JWKS.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry properties) {
        properties.add("adinsight.security.oidc.issuer-uri", () -> ISSUER);
        properties.add("adinsight.security.oidc.audience", () -> "adinsight-api");
        properties.add(
                "adinsight.security.oidc.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopKeys() {
        JWKS.stop(0);
    }

    @Autowired MockMvc mvc;
    @MockitoBean IAccountCatalog accounts;
    @MockitoBean IMembershipDirectory memberships;
    @MockitoBean IExternalIdentityDirectory identities;

    @BeforeEach
    void localIdentity() {
        KEYS_UNAVAILABLE.set(false);
        when(identities.activeUserId(ISSUER, SUBJECT)).thenReturn(Optional.of("local-user"));
        when(memberships.role(TENANT, "local-user")).thenReturn(Optional.of(TenantRole.VIEWER));
        when(accounts.list(TENANT, new PageQuery(0, 20)))
                .thenReturn(new AccountPage(List.of(), 0, 20, false));
    }

    @Test
    void verifiedTokenResolvesLocalUserAndDoesNotCreateSession() throws Exception {
        var response =
                mvc.perform(
                                get("/api/v1/accounts")
                                        .servletPath("/api/v1/accounts")
                                        .header("X-Tenant-Id", TENANT)
                                        .header(
                                                "Authorization",
                                                "Bearer " + token(KEY, claims -> {})))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.size").value(20))
                        .andExpect(header().doesNotExist("Set-Cookie"))
                        .andReturn();
        org.assertj.core.api.Assertions.assertThat(response.getRequest().getSession(false))
                .isNull();
        verify(memberships).role(TENANT, "local-user");
    }

    @Test
    void rejectsWrongIssuerAudienceExpiryNotBeforeSubjectAndSignature() throws Exception {
        var variants =
                List.<Consumer<JwtClaimsSet.Builder>>of(
                        claims -> claims.issuer("https://attacker.example.test"),
                        claims -> claims.audience(List.of("another-api")),
                        claims ->
                                claims.issuedAt(Instant.now().minusSeconds(600))
                                        .expiresAt(Instant.now().minusSeconds(300)),
                        claims -> claims.notBefore(Instant.now().plusSeconds(300)),
                        claims -> claims.claims(values -> values.remove("exp")),
                        claims -> claims.claims(values -> values.remove("sub")));
        for (var variant : variants) {
            reject(token(KEY, variant));
        }
        reject(token(new RSAKeyGenerator(2048).keyID("proof").generate(), claims -> {}));
        reject("not-a-jwt");
        verifyNoInteractions(identities, memberships, accounts);
    }

    @Test
    void unmappedIdentityOrAuthenticationDependencyFailureCannotReadData() throws Exception {
        when(identities.activeUserId(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        reject(token(KEY, claims -> {}));
        when(identities.activeUserId(ISSUER, SUBJECT))
                .thenThrow(new DataAccessResourceFailureException("password=secret"));
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT)
                                .header("Authorization", "Bearer " + token(KEY, claims -> {})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
        verifyNoInteractions(memberships, accounts);
    }

    @Test
    void tokenTenantAndRoleClaimsNeverGrantMembership() throws Exception {
        when(memberships.role(TENANT, "local-user")).thenReturn(Optional.empty());
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT)
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + token(
                                                        KEY,
                                                        claims ->
                                                                claims.claim(
                                                                                "tenant_id",
                                                                                TENANT.toString())
                                                                        .claim(
                                                                                "roles",
                                                                                List.of(
                                                                                        "PLATFORM_ADMIN"))
                                                                        .claim(
                                                                                "realm_access",
                                                                                Map.of(
                                                                                        "roles",
                                                                                        List.of(
                                                                                                "TENANT_ADMIN"))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
        verifyNoInteractions(accounts);
    }

    @Test
    void basicCredentialsAndQueryStringTokenCannotAuthenticateInOidcMode() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"AdInsight\""));
        mvc.perform(get("/api/v1/accounts").param("access_token", token(KEY, claims -> {})))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(identities, memberships, accounts);
    }

    private void reject(String token) throws Exception {
        mvc.perform(
                        get("/api/v1/accounts")
                                .servletPath("/api/v1/accounts")
                                .header("X-Tenant-Id", TENANT)
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unavailableSigningKeysUseSafe503InsteadOfCredentialFailure() throws Exception {
        String needingNewKeys =
                token(new RSAKeyGenerator(2048).keyID("refresh-test").generate(), claims -> {});
        KEYS_UNAVAILABLE.set(true);
        try {
            mvc.perform(
                            get("/api/v1/accounts")
                                    .servletPath("/api/v1/accounts")
                                    .header("X-Tenant-Id", TENANT)
                                    .header("Authorization", "Bearer " + needingNewKeys))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
            verifyNoInteractions(identities, memberships, accounts);
        } finally {
            KEYS_UNAVAILABLE.set(false);
        }
    }

    private static String token(RSAKey key, Consumer<JwtClaimsSet.Builder> customize) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(ISSUER)
                        .subject(SUBJECT)
                        .audience(List.of("adinsight-api"))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300));
        customize.accept(claims);
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256)
                                        .keyId(key.getKeyID())
                                        .build(),
                                claims.build()))
                .getTokenValue();
    }
}
