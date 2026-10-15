package io.datavortex.adinsight.identityaccess.infrastructure.persistence;

import io.datavortex.adinsight.identityaccess.application.port.MembershipDirectory;
import io.datavortex.adinsight.identityaccess.domain.model.TenantRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcMembershipDirectory implements MembershipDirectory {
    private final JdbcTemplate jdbc;

    public JdbcMembershipDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantRole> role(UUID tenantId, String userId) {
        return jdbc
                .query(
                        """
                SELECT m.role FROM memberships m
                JOIN tenants t ON t.id = m.tenant_id
                JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = ? AND m.user_id = ? AND t.status = 'ACTIVE' AND u.status = 'ACTIVE'
                """,
                        (row, index) -> TenantRole.valueOf(row.getString("role")),
                        tenantId.toString(),
                        userId)
                .stream()
                .findFirst();
    }
}
