package br.com.fixflow.shared.application.registration.usecase;

import java.util.UUID;

public interface VerifiedSignup {
    record Confirmation(UUID userId,UUID tenantId,String role,String email) { }
    void request(String company,String name,String email,String password,String plan,String origin);
    record Profile(String document,String phone,br.com.fixflow.shared.application.registration.dto.RegistrationAddress address) { }
    default void request(String company,String name,String email,String password,String plan,String origin,Profile profile) {
        request(company,name,email,password,plan,origin);
    }
    Confirmation confirm(String token);
}
