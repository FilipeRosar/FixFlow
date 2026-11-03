
package br.com.fixflow.shared.infrastructure.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class GlobalAuthenticationService {

    private final AuthIdentityLookup identityLookup;
    private final PasswordEncoder passwordEncoder;

    public GlobalAuthenticationService(
            AuthIdentityLookup identityLookup,
            PasswordEncoder passwordEncoder
    ) {
        this.identityLookup = identityLookup;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthenticatedIdentity authenticate(
            String email,
            String password
    ) {
        if (email == null || email.isBlank()
                || password == null || password.isBlank()) {
            throw new BadCredentialsException(
                    "Credenciais inválidas."
            );
        }

        var identity = identityLookup.findByEmail(email.trim());

        if (identity.isEmpty()) {
            throw new BadCredentialsException(
                    "Credenciais inválidas."
            );
        }

        var user = identity.get();

        if (!"ACTIVE".equals(user.status())
                || !passwordEncoder.matches(
                password,
                user.passwordHash()
        )) {
            throw new BadCredentialsException(
                    "Credenciais inválidas."
            );
        }

        return new AuthenticatedIdentity(
                user.userId(),
                user.email()
        );
    }
}
