
package br.com.fixflow.shared.tenant;

import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizedMembershipTest {

    @Test
    void shouldCreateAuthorizedMembership() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        var membership = new AuthorizedMembership(
                userId,
                tenantId,
                AuthorizedMembership.Role.OWNER
        );

        assertEquals(userId, membership.userId());
        assertEquals(tenantId, membership.tenantId());
        assertEquals(
                AuthorizedMembership.Role.OWNER,
                membership.role()
        );
    }

    @Test
    void shouldRejectNullTenant() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizedMembership(
                        UUID.randomUUID(),
                        null,
                        AuthorizedMembership.Role.ADMIN
                )
        );
    }

    @Test
    void shouldRejectNullRole() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthorizedMembership(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null
                )
        );
    }
}
