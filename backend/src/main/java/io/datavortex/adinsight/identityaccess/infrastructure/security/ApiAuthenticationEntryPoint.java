package io.datavortex.adinsight.identityaccess.infrastructure.security;

import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private static final Logger LOG = LoggerFactory.getLogger(ApiAuthenticationEntryPoint.class);
    private final ErrorWriter errors;
    private final String scheme;

    public ApiAuthenticationEntryPoint(ErrorWriter errors, String scheme) {
        this.errors = errors;
        this.scheme = scheme;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        if (exception instanceof AuthenticationServiceException) {
            boolean databaseFailure = hasDatabaseCause(exception);
            String code = databaseFailure ? "DATABASE_UNAVAILABLE" : "AUTHENTICATION_UNAVAILABLE";
            LOG.error("code={} dependency=authentication", code);
            errors.write(request, response, 503, code, "Authentication dependency unavailable");
            return;
        }
        response.setHeader("WWW-Authenticate", scheme + " realm=\"AdInsight\"");
        errors.write(request, response, 401, "UNAUTHORIZED", "Authentication required");
    }

    private static boolean hasDatabaseCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException) {
                return true;
            }
        }
        return false;
    }
}
