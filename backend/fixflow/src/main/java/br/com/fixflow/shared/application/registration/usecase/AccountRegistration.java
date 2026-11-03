package br.com.fixflow.shared.application.registration.usecase;

import br.com.fixflow.shared.application.registration.dto.RegisteredAccount;
import br.com.fixflow.shared.application.registration.dto.RegistrationAddress;

public interface AccountRegistration {
    RegisteredAccount register(String companyName, String firstName, String lastName, String email, String password, String document, RegistrationAddress address, String phone);
}
