package br.com.fixflow.modules.identity.application.dto;
import java.util.UUID;
public record ManagedUser(UUID userId, String fullName, String email, String role, String status) { }
