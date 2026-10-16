package io.datavortex.adinsight.identityaccess.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("adinsight.security.oidc")
public record OidcSettings(
                @NotBlank(message = "Set OIDC_ISSUER_URI to the Keycloak realm issuer (docs/backend-foundation.md)") String issuerUri,
                @NotBlank(message = "Set OIDC_AUDIENCE to the API audience (docs/backend-foundation.md)") String audience,
                String jwkSetUri) {
        }
        
