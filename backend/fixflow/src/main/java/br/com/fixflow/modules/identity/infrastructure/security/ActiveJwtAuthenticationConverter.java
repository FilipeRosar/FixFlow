package br.com.fixflow.modules.identity.infrastructure.security;

import br.com.fixflow.modules.identity.application.port.SessionStore;
import br.com.fixflow.shared.infrastructure.security.AuthenticatedIdentity;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class ActiveJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final SessionStore store;
    public ActiveJwtAuthenticationConverter(SessionStore store) { this.store = store; }
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID user = UUID.fromString(jwt.getSubject());
            UUID tenant = UUID.fromString(jwt.getClaimAsString("tid"));
            var identity = store.activeMembership(user, tenant).orElseThrow(() -> new BadCredentialsException("Token inválido."));
            if (!identity.role().equals(jwt.getClaimAsString("role"))) throw new BadCredentialsException("Token inválido.");
            return UsernamePasswordAuthenticationToken.authenticated(new AuthenticatedIdentity(user, identity.email(), tenant),
                    null, List.of(new SimpleGrantedAuthority("ROLE_" + identity.role())));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadCredentialsException("Token inválido.");
        }
    }
}
