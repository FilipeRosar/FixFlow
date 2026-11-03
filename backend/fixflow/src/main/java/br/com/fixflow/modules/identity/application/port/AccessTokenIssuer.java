package br.com.fixflow.modules.identity.application.port;

import br.com.fixflow.modules.identity.domain.SessionIdentity;
import java.time.Instant;

public interface AccessTokenIssuer {
    String issue(SessionIdentity identity, Instant issuedAt);
}
