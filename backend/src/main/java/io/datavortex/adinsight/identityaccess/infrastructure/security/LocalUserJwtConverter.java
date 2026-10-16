package io.datavortex.adinsight.identityaccess.infrastructure.security;

import io.datavortex.adinsight.identityaccess.application.port.IExternalIdentityDirectory;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class LocalUserJwtConverter implements Converter<Jwt, JwtAuthenticationToken> {
    private final IExternalIdentityDirectory identities;

    public LocalUserJwtConverter(IExternalIdentityDirectory identities) {
        this.identities = identities;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        if (jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new InvalidBearerTokenException("Local identity required");
        }
        try {
            String userId =
                    identities
                            .activeUserId(jwt.getIssuer().toString(), jwt.getSubject())
                            .orElseThrow(
                                    () ->
                                            new InvalidBearerTokenException(
                                                    "Local identity required"));
            // Tenant roles come only from verified DB memberships, never token claims.
            return new JwtAuthenticationToken(jwt, List.of(), userId);
        } catch (DataAccessException exception) {
            throw new InternalAuthenticationServiceException(
                    "Identity dependency unavailable", exception);
        }
    }
}
