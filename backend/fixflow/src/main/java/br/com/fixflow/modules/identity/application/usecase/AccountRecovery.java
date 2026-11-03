package br.com.fixflow.modules.identity.application.usecase;
public interface AccountRecovery {
    void requestReset(String email);
    void resetPassword(String token, String password);
    void logout(String refreshToken);
}
