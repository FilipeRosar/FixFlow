package br.com.fixflow.shared.infrastructure.tenant;


import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TenantDatabaseAccess {

    @PersistenceContext
    private EntityManager entityManager;

    private final TenantTransaction tenantTransaction;

    @Transactional(readOnly = true)
    public List<UUID> findVisibleTenantsIds(){
        tenantTransaction.configureCurrentTenant();

        @SuppressWarnings("unchecked")
        List<UUID> tenantIds = entityManager
                .createNativeQuery("SELECT id FROM tenants")
                .getResultList();
        return tenantIds;
    }

    @Transactional(readOnly = true)
    public List<String> findVisibleUserEmails() {
        tenantTransaction.configureCurrentTenant();

        @SuppressWarnings("unchecked")
        List<String> emails = entityManager
                .createNativeQuery(
                        "SELECT email FROM app_users ORDER BY email"
                )
                .getResultList();

        return emails;
    }

}
