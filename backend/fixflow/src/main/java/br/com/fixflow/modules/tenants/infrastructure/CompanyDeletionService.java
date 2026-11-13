package br.com.fixflow.modules.tenants.infrastructure;

import br.com.fixflow.modules.identity.application.port.SessionStore;
import br.com.fixflow.modules.subscriptions.application.Subscriptions;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;

@Service
public class CompanyDeletionService implements br.com.fixflow.modules.tenants.application.CompanyDeletion {
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;
    private final SessionStore sessions;
    private final PasswordEncoder encoder;
    private final Subscriptions subscriptions;
    private final JdbcTemplate jdbc;
    public CompanyDeletionService(AuthorizedTenantOperations tenants,CurrentAuthenticatedIdentity identity,
            SessionStore sessions,PasswordEncoder encoder,Subscriptions subscriptions,JdbcTemplate jdbc) {
        this.tenants=tenants;this.identity=identity;this.sessions=sessions;this.encoder=encoder;this.subscriptions=subscriptions;this.jdbc=jdbc;
    }
    @Override public void delete(String password) {
        var actor=identity.require();
        if(actor.tenantId()==null) throw new AccessDeniedException("Selecione a empresa.");
        tenants.execute(actor.tenantId(),m->{
            if(!m.role().name().equals("OWNER")) throw new AccessDeniedException("Somente o proprietário pode excluir a empresa.");
            return null;
        });
        boolean valid=sessions.withLockedAccount(actor.email(),account->{
            if(account==null || !account.userId().equals(actor.userId()) || !account.canAuthenticateAt(Instant.now())) return false;
            if(!encoder.matches(password,account.passwordHash())) { sessions.recordFailure(actor.userId());return false; }
            sessions.resetFailures(actor.userId());return true;
        });
        if(!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Senha incorreta ou conta temporariamente bloqueada.");
        // Finish remote cancellation before the irreversible local deletion. A gateway failure keeps company data intact.
        subscriptions.cancel();
        tenants.execute(actor.tenantId(),m->{
            jdbc.queryForObject("SELECT public.delete_company(?,?)",Object.class,actor.userId(),m.tenantId());
            return null;
        });
    }
}
