package br.com.fixflow.modules.identity.infrastructure.persistence;

import br.com.fixflow.modules.identity.application.port.SessionStore;
import br.com.fixflow.modules.identity.domain.LoginAccount;
import br.com.fixflow.modules.identity.domain.RefreshSession;
import br.com.fixflow.modules.identity.domain.SessionIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class JdbcSessionStore implements SessionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcSessionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T withLockedAccount(String email, Function<LoginAccount, T> operation) {
        return transaction.execute(status -> {
            var rows = jdbc.query("SELECT * FROM public.auth_lock_account(?)", (rs, row) ->
                    new LoginAccount(rs.getObject("user_id", UUID.class), rs.getString("email"),
                            rs.getString("password_hash"), rs.getString("status"), instant(rs, "locked_until")), email);
            return operation.apply(rows.isEmpty() ? null : rows.getFirst());
        });
    }

    @Override
    public <T> T withLockedRefresh(String tokenHash, Function<RefreshSession, T> operation) {
        return transaction.execute(status -> {
            var rows = jdbc.query("SELECT * FROM public.auth_lock_refresh(?)", (rs, row) ->
                    new RefreshSession(rs.getObject("token_id", UUID.class), rs.getObject("family_id", UUID.class),
                            rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                            instant(rs, "expires_at"), instant(rs, "absolute_expires_at"),
                            instant(rs, "used_at"), instant(rs, "revoked_at")), tokenHash);
            return operation.apply(rows.isEmpty() ? null : rows.getFirst());
        });
    }

    @Override
    public Optional<SessionIdentity> activeMembership(UUID userId, UUID tenantId) {
        return jdbc.query("SELECT * FROM public.auth_active_membership(?, ?)", (rs, row) ->
                new SessionIdentity(rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getObject("tenant_id", UUID.class), rs.getString("role")), userId, tenantId).stream().findFirst();
    }

    @Override
    public void recordFailure(UUID userId) { jdbc.queryForObject("SELECT public.auth_record_failure(?)", Object.class, userId); }
    @Override
    public void resetFailures(UUID userId) { jdbc.queryForObject("SELECT public.auth_reset_failures(?)", Object.class, userId); }
    @Override
    public void revokeFamily(UUID familyId) { jdbc.queryForObject("SELECT public.auth_revoke_family(?)", Object.class, familyId); }
    @Override
    public Instant createSession(UUID familyId, SessionIdentity identity, UUID tokenId, String hash) {
        return jdbc.queryForObject("SELECT public.auth_create_session(?, ?, ?, ?, ?)", java.sql.Timestamp.class,
                familyId, identity.userId(), identity.tenantId(), tokenId, hash).toInstant();
    }
    @Override
    public Instant rotate(UUID oldToken, UUID newToken, String hash) {
        return jdbc.queryForObject("SELECT public.auth_rotate_refresh(?, ?, ?)", java.sql.Timestamp.class,
                oldToken, newToken, hash).toInstant();
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
