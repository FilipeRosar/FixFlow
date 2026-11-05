package br.com.fixflow.modules.identity.infrastructure.persistence;
import br.com.fixflow.modules.identity.application.port.AccountRecoveryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
@Repository
public class JdbcAccountRecoveryStore implements AccountRecoveryStore {
    private final JdbcTemplate jdbc;
    public JdbcAccountRecoveryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Optional<String> issue(String email, String tokenHash) {
        return jdbc.query("SELECT * FROM public.auth_issue_password_reset(?, ?)", (rs, n) -> rs.getString(1), email, tokenHash).stream().findFirst();
    }
    @Transactional
    public boolean consume(String tokenHash, String passwordHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.auth_consume_password_reset(?, ?)", Boolean.class, tokenHash, passwordHash));
    }
}
