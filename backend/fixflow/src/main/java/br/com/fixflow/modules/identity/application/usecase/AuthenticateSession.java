package br.com.fixflow.modules.identity.application.usecase;

import br.com.fixflow.modules.identity.application.dto.SessionTokens;
import br.com.fixflow.modules.identity.application.exception.SessionRejectedException;
import br.com.fixflow.modules.identity.application.port.AccessTokenIssuer;
import br.com.fixflow.modules.identity.application.port.PasswordVerifier;
import br.com.fixflow.modules.identity.application.port.RefreshSecrets;
import br.com.fixflow.modules.identity.application.port.SessionStore;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

public class AuthenticateSession implements LoginUseCase, RefreshSessionUseCase {
    private final SessionStore store;
    private final PasswordVerifier passwords;
    private final AccessTokenIssuer tokens;
    private final RefreshSecrets secrets;
    private final Clock clock;

    public AuthenticateSession(SessionStore store, PasswordVerifier passwords, AccessTokenIssuer tokens,
                               RefreshSecrets secrets, Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.tokens = tokens;
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public SessionTokens login(String email, String password, UUID tenantId) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new SessionRejectedException();
        SessionTokens result = store.withLockedAccount(email.strip(), account -> {
            if (account == null || !account.canAuthenticateAt(clock.instant())) {
                passwords.simulateVerification(password);
                return null;
            }
            if (!passwords.matches(password, account.passwordHash())) {
                store.recordFailure(account.userId());
                return null;
            }
            var identity = store.activeMembership(account.userId(), tenantId);
            if (identity.isEmpty()) return null;
            store.resetFailures(account.userId());
            String refresh = secrets.generate();
            var now = clock.instant();
            var expiry = store.createSession(UUID.randomUUID(), identity.get(), UUID.randomUUID(), secrets.hash(refresh));
            return new SessionTokens(tokens.issue(identity.get(), now), refresh, 900, expiry, identity.get());
        });
        // Throw only after committing failed-attempt bookkeeping.
        if (result == null) throw new SessionRejectedException();
        return result;
    }

    @Override
    public SessionTokens refresh(String refreshToken) {
        if (refreshToken == null || !refreshToken.matches("[A-Za-z0-9_-]{43}")) throw new SessionRejectedException();
        SessionTokens result = store.withLockedRefresh(secrets.hash(refreshToken), session -> {
            if (session == null) return null;
            if (session.usedAt() != null) {
                store.revokeFamily(session.familyId());
                return null;
            }
            if (!session.isUsableAt(clock.instant())) return null;
            var identity = store.activeMembership(session.userId(), session.tenantId());
            if (identity.isEmpty()) {
                store.revokeFamily(session.familyId());
                return null;
            }
            String next = secrets.generate();
            var expiry = store.rotate(session.tokenId(), UUID.randomUUID(), secrets.hash(next));
            return new SessionTokens(tokens.issue(identity.get(), clock.instant()), next, 900, expiry, identity.get());
        });
        // Reuse revocation must survive the rejected HTTP request.
        if (result == null) throw new SessionRejectedException();
        return result;
    }
}
