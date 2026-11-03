package br.com.fixflow.shared.infrastructure.outbox;

import br.com.fixflow.shared.application.outbox.OutboxService;
import br.com.fixflow.shared.infrastructure.tenant.TenantContext;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class TransactionalOutboxService implements OutboxService {
    private final JdbcTemplate jdbc;
    private final TenantTransaction tenantTransaction;

    public TransactionalOutboxService(JdbcTemplate jdbc, TenantTransaction tenantTransaction) {
        this.jdbc = jdbc;
        this.tenantTransaction = tenantTransaction;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(String aggregateType, UUID aggregateId, String eventType, String payload) {
        requireLabel(aggregateType);
        requireLabel(eventType);
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        tenantTransaction.configureCurrentTenant();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO public.outbox_event (id, tenant_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, id, TenantContext.requireTenantId(), aggregateType, aggregateId, eventType, payload);
        return id;
    }

    private static void requireLabel(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Tipo deve conter entre 1 e 100 caracteres.");
        }
    }
}
