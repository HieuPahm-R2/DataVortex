package io.datavortex.adinsight.identityaccess.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OidcSettingsTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(SettingsOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OidcSettings.class)
    static class SettingsOnly {}

    @Test
    void issuerAndAudienceAreRequiredWithNoImplicitAuthenticationFallback() {
        context.run(
                result ->
                        assertThat(result.getStartupFailure())
                                .hasRootCauseInstanceOf(BindValidationException.class));
        context.withPropertyValues(
                        "adinsight.security.oidc.issuer-uri=https://identity.example.test/realms/adinsight")
                .run(
                        result ->
                                assertThat(result.getStartupFailure())
                                        .hasRootCauseInstanceOf(BindValidationException.class));
        context.withPropertyValues(
                        "adinsight.security.oidc.issuer-uri=https://identity.example.test/realms/adinsight",
                        "adinsight.security.oidc.audience=adinsight-api")
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(OidcSettings.class));
    }
}
