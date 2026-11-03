package br.com.fixflow.shared.infrastructure.tenant;


import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TenantTransaction {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void configureCurrentTenant(){
        UUID tenantId = TenantContext.requireTenantId();

        entityManager.createNativeQuery(
                "SELECT set_config('app.tenant_id', :tenantId, true)"
        )
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();
    }
}
