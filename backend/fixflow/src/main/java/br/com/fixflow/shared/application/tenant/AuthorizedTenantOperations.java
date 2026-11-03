package br.com.fixflow.shared.application.tenant;

import java.util.UUID;
import java.util.function.Function;

public interface AuthorizedTenantOperations {
    <T> T execute(UUID requestedTenantId, Function<AuthorizedMembership, T> operation);
}
