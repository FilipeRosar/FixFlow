package br.com.fixflow.shared.infrastructure.security;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentAuthenticatedIdentity {
    public AuthenticatedIdentity require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedIdentity identity)) {
            throw new InsufficientAuthenticationException("Autenticação obrigatória.");
        }
        return identity;
    }
}
