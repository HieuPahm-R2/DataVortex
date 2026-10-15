package io.datavortex.adinsight.identityaccess.interfaces.http;

import io.datavortex.adinsight.identityaccess.application.port.MembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantScope;
import io.datavortex.adinsight.shared.interfaces.http.ErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantFilter extends OncePerRequestFilter {
    private final MembershipDirectory memberships;
    private final ErrorWriter errors;

    public TenantFilter(MembershipDirectory memberships, ErrorWriter errors) {
        this.memberships = memberships;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (principal == null
                || !principal.isAuthenticated()
                || principal instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("X-Tenant-Id");
        UUID tenantId;
        try {
            tenantId = UUID.fromString(header == null ? "" : header);
            if (!tenantId.toString().equalsIgnoreCase(header)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            errors.write(request, response, 400, "INVALID_TENANT", "X-Tenant-Id must be a UUID");
            return;
        }
        try {
            var role = memberships.role(tenantId, principal.getName());
            if (role.isEmpty()) {
                errors.write(
                        request, response, 403, "TENANT_FORBIDDEN", "Tenant membership required");
                return;
            }
            request.setAttribute(
                    TenantScope.class.getName(),
                    new TenantScope(tenantId, principal.getName(), role.get()));
        } catch (DataAccessException exception) {
            errors.write(
                    request,
                    response,
                    503,
                    "DATABASE_UNAVAILABLE",
                    "Authorization dependency unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
