package io.datavortex.adinsight.identityaccess.infrastructure.config;

import io.datavortex.adinsight.identityaccess.application.port.IExternalIdentityDirectory;
import io.datavortex.adinsight.identityaccess.application.port.IMembershipDirectory;
import io.datavortex.adinsight.identityaccess.infrastructure.security.ApiAuthenticationEntryPoint;
import io.datavortex.adinsight.identityaccess.infrastructure.security.LocalUserJwtConverter;
import io.datavortex.adinsight.identityaccess.interfaces.http.TenantFilter;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
@Profile("!local")
@EnableConfigurationProperties(OidcSettings.class)
public class OidcSecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(OidcSettings settings) {
        var decoder =
                settings.jwkSetUri() == null || settings.jwkSetUri().isBlank()
                        ? NimbusJwtDecoder.withIssuerLocation(settings.issuerUri()).build()
                        : NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri()).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(settings.issuerUri()),
                        new JwtClaimValidator<List<String>>(
                                "aud",
                                audience ->
                                        audience != null && audience.contains(settings.audience())),
                        new JwtClaimValidator<Instant>("exp", java.util.Objects::nonNull),
                        new JwtClaimValidator<String>(
                                "sub", subject -> subject != null && !subject.isBlank())));
        return decoder;
    }

    @Bean
    SecurityFilterChain oidcFilterChain(
            HttpSecurity http,
            JwtDecoder decoder,
            IExternalIdentityDirectory identities,
            IMembershipDirectory memberships,
            ErrorWriter errors)
            throws Exception {
        var entryPoint = new ApiAuthenticationEntryPoint(errors, "Bearer");
        AccessDeniedHandler denied =
                (request, response, exception) ->
                        errors.write(request, response, 403, "FORBIDDEN", "Access denied");
        // Bearer credentials are accepted only in Authorization, never cookies or URL parameters.
        // Browser login/refresh/logout belongs to Keycloak and its OIDC client.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(
                        handler ->
                                handler.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(denied)
                                        .jwt(
                                                jwt ->
                                                        jwt.decoder(decoder)
                                                                .jwtAuthenticationConverter(
                                                                        new LocalUserJwtConverter(
                                                                                identities)))
                                        .withObjectPostProcessor(
                                                authenticationFailures(entryPoint)))
                .addFilterAfter(
                        new TenantFilter(memberships, errors), AnonymousAuthenticationFilter.class)
                .build();
    }

    private static ObjectPostProcessor<BearerTokenAuthenticationFilter> authenticationFailures(
            AuthenticationEntryPoint entryPoint) {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                var failures = new AuthenticationEntryPointFailureHandler(entryPoint);
                // Dependency failures must use the safe 503 contract too.
                failures.setRethrowAuthenticationServiceException(false);
                filter.setAuthenticationFailureHandler(failures);
                return filter;
            }
        };
    }
}
