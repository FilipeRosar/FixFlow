package br.com.fixflow.shared.web.registration.controller;

import br.com.fixflow.shared.application.registration.usecase.AccountRegistration;
import br.com.fixflow.shared.application.registration.dto.RegisteredAccount;
import br.com.fixflow.shared.web.registration.dto.RegistrationRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountRegistrationController {
    private final AccountRegistration registration;

    public AccountRegistrationController(AccountRegistration registration) {
        this.registration = registration;
    }

    @PostMapping("/api/contas")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredAccount register(@Valid @RequestBody RegistrationRequest request) {
        return registration.register(request.companyName(), request.firstName(), request.lastName(), request.email(), request.password(),
                request.document(), request.address().toApplication(), request.phone());
    }
}
