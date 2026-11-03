package br.com.fixflow.modules.identity.infrastructure.security;

import br.com.fixflow.modules.identity.application.port.PasswordVerifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BcryptPasswordVerifier implements PasswordVerifier {
    private final PasswordEncoder encoder;
    private final String dummyHash;
    public BcryptPasswordVerifier(PasswordEncoder encoder) {
        this.encoder = encoder;
        dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }
    @Override
    public boolean matches(String raw, String hash) { return encoder.matches(raw, hash); }
    @Override
    public void simulateVerification(String raw) { encoder.matches(raw, dummyHash); }
}
