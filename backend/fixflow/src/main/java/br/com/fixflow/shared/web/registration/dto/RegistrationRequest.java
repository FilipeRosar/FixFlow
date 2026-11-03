package br.com.fixflow.shared.web.registration.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegistrationRequest(
        @NotBlank
        @Size(max = 150)
        String companyName,
        @NotBlank
        @Size(max = 150)
        String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 72) String password) {
    public RegistrationRequest {
        companyName = companyName == null ? null : companyName.strip();
        fullName = fullName == null ? null : fullName.strip();
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "RegistrationRequest[redacted]";
    }
}
