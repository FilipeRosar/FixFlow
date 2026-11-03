
package br.com.fixflow.shared.infrastructure.tenant;

import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantMembershipAuthorizationRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantMembershipAuthorizationRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AuthorizedMembership> authorize(
            UUID userId,
            UUID tenantId
    ) {
        if (userId == null || tenantId == null) {
            throw new IllegalArgumentException(
                    "userId e tenantId são obrigatórios"
            );
        }

        var memberships = jdbcTemplate.query(
                """
                SELECT
                    authorized_user_id,
                    authorized_tenant_id,
                    authorized_role
                FROM public.authorize_tenant_membership(?, ?)
                """,
                (rs, rowNum) -> new AuthorizedMembership(
                        rs.getObject(
                                "authorized_user_id",
                                UUID.class
                        ),
                        rs.getObject(
                                "authorized_tenant_id",
                                UUID.class
                        ),
                        AuthorizedMembership.Role.valueOf(
                                rs.getString("authorized_role")
                        )
                ),
                userId,
                tenantId
        );

        return memberships.stream().findFirst();
    }
}
