package br.com.fixflow.shared.infrastructure.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GlobalAuthenticationProvider implements AuthenticationProvider {
    private final GlobalAuthenticationService service;

    public GlobalAuthenticationProvider(GlobalAuthenticationService service) {
        this.service = service;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var identity = service.authenticate(authentication.getName(),
                authentication.getCredentials() instanceof String password ? password : null);
        return UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
