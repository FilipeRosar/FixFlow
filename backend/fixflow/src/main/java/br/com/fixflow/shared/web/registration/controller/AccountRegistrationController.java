package br.com.fixflow.shared.web.registration.controller;

import br.com.fixflow.shared.application.registration.usecase.VerifiedSignup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountRegistrationController {
    private final VerifiedSignup signup;
    public AccountRegistrationController(VerifiedSignup signup) { this.signup=signup; }
    public record Request(@NotBlank @Size(max=150) String companyName,@NotBlank @Size(max=301) String fullName,
        @NotBlank @Email @Size(max=254) String email,@NotBlank @Size(min=8,max=72)
        @Pattern(regexp="^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$") String password,
        @NotBlank @Pattern(regexp="STARTER|PROFESSIONAL|BUSINESS") String plan,
        @Pattern(regexp="(?:[0-9]{11}|[0-9]{14})") String document,
        @Pattern(regexp="[0-9]{10,11}") String phone,
        @Valid br.com.fixflow.shared.web.registration.dto.RegistrationRequest.Address address) {
        public Request {
            companyName=companyName==null?null:companyName.strip();
            fullName=fullName==null?null:fullName.strip();
            email=email==null?null:email.strip();
        }
        @Override public String toString(){return "SignupRequest[redacted]";}
    }
    public record Token(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString(){return "SignupConfirmation[redacted]";}
    }
    public record Accepted(String message) { }
    @PostMapping("/api/contas") @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted register(@Valid @RequestBody Request request,HttpServletRequest http) {
        signup.request(request.companyName(),request.fullName(),request.email(),request.password(),request.plan(),http.getRemoteAddr(),
            request.address()==null?null:new VerifiedSignup.Profile(request.document(),request.phone(),request.address().toApplication()));
        return new Accepted("Se o cadastro puder continuar, você receberá um e-mail de confirmação. Confira também o spam. Para reenviar, aguarde um minuto e envie o formulário novamente.");
    }
    @PostMapping("/api/contas/confirmar")
    public VerifiedSignup.Confirmation confirm(@Valid @RequestBody Token request) { return signup.confirm(request.token()); }
}
