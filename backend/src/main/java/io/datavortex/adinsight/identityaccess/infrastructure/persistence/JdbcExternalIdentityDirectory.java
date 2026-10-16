package io.datavortex.adinsight.identityaccess.infrastructure.persistence;

import io.datavortex.adinsight.identityaccess.application.port.IExternalIdentityDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExternalIdentityDirectory implements IExternalIdentityDirectory {
    private final JdbcTemplate jdbc;

    public JdbcExternalIdentityDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> activeUserId(String issuer, String subject) {
        return jdbc
                .query(
                        """
                SELECT u.id FROM user_identities i JOIN users u ON u.id = i.user_id
                WHERE i.issuer = ? AND i.subject = ? AND u.status = 'ACTIVE'
                """,
                        (row, index) -> row.getString("id"),
                        issuer,
                        subject)
                .stream()
                .findFirst();
    }
}
