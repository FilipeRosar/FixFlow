
package br.com.fixflow.shared.infrastructure.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthIdentityLookup {

    private final JdbcTemplate jdbcTemplate;

    public AuthIdentityLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthIdentityRecord> findByEmail(String email) {
        var identities = jdbcTemplate.query(
                """
                SELECT
                    user_id,
                    user_email,
                    password_hash,
                    user_status
                FROM public.find_auth_identity(?)
                """,
                (rs, rowNum) -> new AuthIdentityRecord(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("user_email"),
                        rs.getString("password_hash"),
                        rs.getString("user_status")
                ),
                email
        );

        return identities.stream().findFirst();
    }

    public record AuthIdentityRecord(
            UUID userId,
            String email,
            String passwordHash,
            String status
    ) {
    }
}
