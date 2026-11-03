package br.com.fixflow.shared.application.registration.usecase;

import br.com.fixflow.shared.application.registration.dto.RegisteredAccount;

public interface AccountRegistration {
    RegisteredAccount register(String companyName, String fullName, String email, String password);
}
