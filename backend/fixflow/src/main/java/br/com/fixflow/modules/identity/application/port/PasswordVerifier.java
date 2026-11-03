package br.com.fixflow.modules.identity.application.port;

public interface PasswordVerifier {
    boolean matches(String raw, String hash);
    void simulateVerification(String raw);
}
