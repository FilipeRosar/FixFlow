package br.com.fixflow.shared.application.tenant;
import java.util.UUID;
public record SelectedTenant(UUID userId, UUID tenantId, AuthorizedMembership.Role role, String companyName) { }
