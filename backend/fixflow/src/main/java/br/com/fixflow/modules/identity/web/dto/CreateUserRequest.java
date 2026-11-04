package br.com.fixflow.modules.identity.web.dto;
import jakarta.validation.constraints.*;
public record CreateUserRequest(
        @NotBlank
        @Size(max=150) String fullName,
        @NotBlank
        @Email
        @Size(max=254)
        String email,
        @NotBlank
        @Size(min=10, max=72)
        String password,
        @NotNull @Pattern(regexp="OWNER|ATTENDANT|TECHNICIAN") String role) { }
