
package br.com.fixflow.shared.security;

import br.com.fixflow.shared.infrastructure.security.AuthIdentityLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("integration-test")
@Transactional
class AuthIdentityLookupIntegrationTest {

    @Autowired
    private AuthIdentityLookup authIdentityLookup;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnEmptyForUnknownEmail() {
        var result = authIdentityLookup.findByEmail(
                "unknown-auth-test@example.invalid"
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotAllowDirectUsersSelect() {
        var exception = assertThrows(
                Exception.class,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM public.users",
                        Integer.class
                )
        );

        assertNotNull(exception);
    }

    @Test
    void shouldConnectAsRuntimeRole() {
        String currentUser = jdbcTemplate.queryForObject(
                "SELECT current_user",
                String.class
        );

        assertEquals("fixflow_runtime", currentUser);
    }

}
