package br.com.fixflow.modules.identity.application.port;
import java.util.Optional;
public interface AccountRecoveryStore {
    Optional<String> issue(String email, String tokenHash);
    boolean consume(String tokenHash, String passwordHash);
}
