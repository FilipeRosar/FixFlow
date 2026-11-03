package br.com.fixflow.modules.identity.web.dto;
import jakarta.validation.constraints.*;
public record ResetPasswordRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])[\\s\\S]*$") String newPassword) {
    @Override public String toString() { return "ResetPasswordRequest[redacted]"; }
}
