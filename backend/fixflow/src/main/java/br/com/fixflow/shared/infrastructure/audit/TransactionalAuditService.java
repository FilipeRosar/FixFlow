package br.com.fixflow.shared.infrastructure.audit;

import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class TransactionalAuditService implements AuditService {
    @PersistenceContext
    private EntityManager entityManager;
    private final TenantTransaction tenantTransaction;

    public TransactionalAuditService(TenantTransaction tenantTransaction) {
        this.tenantTransaction = tenantTransaction;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(UUID actorId, String action, String resourceType, UUID resourceId, String details) {
        requireLabel(action, "action");
        requireLabel(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(details, "details");
        UUID tenantId = TenantContext.requireTenantId();
        tenantTransaction.configureCurrentTenant();
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO public.audit_log
                    (id, tenant_id, actor_id, action, resource_type, resource_id, details)
                VALUES (:id, :tenant, :actor, :action, :type, :resource, CAST(:details AS jsonb))
                """)
                .setParameter("id", id).setParameter("tenant", tenantId)
                .setParameter("actor", actorId).setParameter("action", action)
                .setParameter("type", resourceType).setParameter("resource", resourceId)
                .setParameter("details", details).executeUpdate();
        return id;
    }

    private static void requireLabel(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException(name + " deve conter entre 1 e 100 caracteres.");
        }
    }
}
