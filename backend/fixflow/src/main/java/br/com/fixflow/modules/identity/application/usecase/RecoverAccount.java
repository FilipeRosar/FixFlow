package br.com.fixflow.modules.identity.application.usecase;
import br.com.fixflow.modules.identity.application.port.*;
import br.com.fixflow.modules.identity.application.exception.ResetRejectedException;
import java.nio.charset.StandardCharsets;
public class RecoverAccount implements AccountRecovery {
    private final AccountRecoveryStore recovery;
    private final SessionStore sessions;
    private final RefreshSecrets secrets;
    private final PasswordHasher passwords;
    private final ResetMail mail;
    public RecoverAccount(AccountRecoveryStore recovery, SessionStore sessions, RefreshSecrets secrets, PasswordHasher passwords, ResetMail mail) {
        this.recovery = recovery; this.sessions = sessions; this.secrets = secrets; this.passwords = passwords; this.mail = mail;
    }
    public void requestReset(String email) {
        String token = secrets.generate();
        recovery.issue(email.strip(), secrets.hash(token)).ifPresent(recipient -> mail.send(recipient, token));
    }
    public void resetPassword(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}") || password == null || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !password.matches("^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$")) throw new ResetRejectedException();
        if (!recovery.consume(secrets.hash(token), passwords.hash(password))) throw new ResetRejectedException();
    }
    public void logout(String refreshToken) {
        if (refreshToken == null || !refreshToken.matches("[A-Za-z0-9_-]{43}")) return;
        sessions.withLockedRefresh(secrets.hash(refreshToken), session -> {
            if (session != null) sessions.revokeFamily(session.familyId());
            return null;
        });
    }
}
