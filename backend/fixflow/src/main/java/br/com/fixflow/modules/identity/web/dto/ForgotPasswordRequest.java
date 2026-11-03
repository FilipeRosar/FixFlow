package br.com.fixflow.modules.identity.web.dto;
import jakarta.validation.constraints.*;
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 254) String email) {
    public ForgotPasswordRequest { email = email == null ? null : email.strip(); }
    @Override public String toString() { return "ForgotPasswordRequest[redacted]"; }
}
