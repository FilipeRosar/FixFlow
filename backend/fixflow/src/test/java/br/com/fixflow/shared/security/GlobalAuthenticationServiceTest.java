
package br.com.fixflow.shared.security;

import br.com.fixflow.shared.infrastructure.security.AuthIdentityLookup;
import br.com.fixflow.shared.infrastructure.security.AuthIdentityLookup.AuthIdentityRecord;
import br.com.fixflow.shared.infrastructure.security.GlobalAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalAuthenticationServiceTest {

    private AuthIdentityLookup identityLookup;
    private GlobalAuthenticationService service;

    private final BCryptPasswordEncoder encoder =
            new BCryptPasswordEncoder(4);

    private final UUID userId =
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

    @BeforeEach
    void setUp() {
        identityLookup = mock(AuthIdentityLookup.class);
        service = new GlobalAuthenticationService(
                identityLookup,
                encoder
        );
    }

    @Test
    void shouldAuthenticateValidCredentials() {
        when(identityLookup.findByEmail("teste@example.com"))
                .thenReturn(Optional.of(new AuthIdentityRecord(
                        userId,
                        "teste@example.com",
                        encoder.encode("SenhaSegura123!"),
                        "ACTIVE"
                )));

        var identity = service.authenticate(
                "teste@example.com",
                "SenhaSegura123!"
        );

        assertEquals(userId, identity.userId());
        assertEquals("teste@example.com", identity.email());
    }

    @Test
    void shouldRejectInvalidPassword() {
        when(identityLookup.findByEmail("teste@example.com"))
                .thenReturn(Optional.of(new AuthIdentityRecord(
                        userId,
                        "teste@example.com",
                        encoder.encode("SenhaCorreta123!"),
                        "ACTIVE"
                )));

        assertThrows(
                BadCredentialsException.class,
                () -> service.authenticate(
                        "teste@example.com",
                        "SenhaIncorreta123!"
                )
        );
    }

    @Test
    void shouldRejectUnknownEmail() {
        when(identityLookup.findByEmail("inexistente@example.com"))
                .thenReturn(Optional.empty());

        assertThrows(
                BadCredentialsException.class,
                () -> service.authenticate(
                        "inexistente@example.com",
                        "QualquerSenha123!"
                )
        );
    }

    @Test
    void shouldRejectSuspendedUser() {
        when(identityLookup.findByEmail("teste@example.com"))
                .thenReturn(Optional.of(new AuthIdentityRecord(
                        userId,
                        "teste@example.com",
                        encoder.encode("SenhaSegura123!"),
                        "SUSPENDED"
                )));

        assertThrows(
                BadCredentialsException.class,
                () -> service.authenticate(
                        "teste@example.com",
                        "SenhaSegura123!"
                )
        );
    }

    @Test
    void shouldRejectBlankCredentials() {
        assertThrows(
                BadCredentialsException.class,
                () -> service.authenticate("", "")
        );

        verifyNoInteractions(identityLookup);
    }
}
