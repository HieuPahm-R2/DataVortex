package io.datavortex.adinsight.identityaccess.infrastructure.security;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@Profile("local")
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public DatabaseUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return jdbc
                .query(
                        "SELECT id, password_hash FROM users WHERE email = ? AND status = 'ACTIVE' AND password_hash IS NOT NULL",
                        (row, index) ->
                                User.withUsername(row.getString("id"))
                                        .password(row.getString("password_hash"))
                                        .authorities("AUTHENTICATED")
                                        .build(),
                        email)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
