
package br.com.fixflow.shared.infrastructure.tenant;

import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

@Service
public class AuthorizedTenantExecutor implements AuthorizedTenantOperations {

    private static final Logger log = LoggerFactory.getLogger(AuthorizedTenantExecutor.class);

    private final CurrentAuthenticatedIdentity currentIdentity;

    private final TenantMembershipAuthorizationRepository repository;
    private final TenantTransaction tenantTransaction;
    private final TransactionTemplate transactionTemplate;

    public AuthorizedTenantExecutor(
            TenantMembershipAuthorizationRepository repository,
            TenantTransaction tenantTransaction,
            PlatformTransactionManager transactionManager,
            CurrentAuthenticatedIdentity currentIdentity
    ) {
        this.repository = repository;
        this.currentIdentity = currentIdentity;
        this.tenantTransaction = tenantTransaction;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                Propagation.REQUIRES_NEW.value()
        );
    }

    @Override
    public <T> T execute(
            UUID requestedTenantId,
            Function<AuthorizedMembership, T> operation
    ) {
        var identity = currentIdentity.require();
        Objects.requireNonNull(requestedTenantId, "Tenant obrigatório");
        Objects.requireNonNull(operation, "Operação obrigatória");

        return transactionTemplate.execute(status -> {

            AuthorizedMembership membership = repository
                    .authorize(identity.userId(), requestedTenantId)
                    .orElseThrow(() -> new AccessDeniedException(
                            "Acesso à oficina não autorizado."
                    ));

            final Object[] result = new Object[1];

            TenantScope.run(membership.tenantId(), () -> {
                tenantTransaction.configureCurrentTenant();
                try {
                    result[0] = operation.apply(membership);
                    log.info("Authorized tenant operation completed");
                } catch (RuntimeException | Error exception) {
                    log.warn("Authorized tenant operation failed");
                    throw exception;
                }
            });

            @SuppressWarnings("unchecked")
            T value = (T) result[0];

            return value;
        });
    }
}
