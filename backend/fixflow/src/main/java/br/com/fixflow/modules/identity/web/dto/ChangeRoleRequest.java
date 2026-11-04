package br.com.fixflow.modules.identity.web.dto;
import jakarta.validation.constraints.*;
public record ChangeRoleRequest(@NotNull @Pattern(regexp="OWNER|ATTENDANT|TECHNICIAN") String role) { }
