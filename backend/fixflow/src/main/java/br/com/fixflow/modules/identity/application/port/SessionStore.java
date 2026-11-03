package br.com.fixflow.modules.identity.application.port;

import br.com.fixflow.modules.identity.domain.LoginAccount;
import br.com.fixflow.modules.identity.domain.RefreshSession;
import br.com.fixflow.modules.identity.domain.SessionIdentity;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Function;

public interface SessionStore {
    <T> T withLockedAccount(String email, Function<LoginAccount, T> operation);
    <T> T withLockedRefresh(String tokenHash, Function<RefreshSession, T> operation);
    Optional<SessionIdentity> activeMembership(UUID userId, UUID tenantId);
    void recordFailure(UUID userId);
    void resetFailures(UUID userId);
    Instant createSession(UUID familyId, SessionIdentity identity, UUID tokenId, String hash);
    Instant rotate(UUID oldToken, UUID newToken, String hash);
    void revokeFamily(UUID familyId);
}
