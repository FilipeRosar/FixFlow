package br.com.fixflow.modules.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 72) String password, UUID tenantId) {
    public LoginRequest { email = email == null ? null : email.strip(); }
    @Override
    public String toString() { return "LoginRequest[redacted]"; }
}
