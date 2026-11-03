package br.com.fixflow.modules.identity.infrastructure.security;
import br.com.fixflow.modules.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
@Component
public class BcryptPasswordHasher implements PasswordHasher {
    private final PasswordEncoder encoder;
    public BcryptPasswordHasher(PasswordEncoder encoder) { this.encoder = encoder; }
    public String hash(String password) { return encoder.encode(password); }
}
