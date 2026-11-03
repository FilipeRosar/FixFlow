package br.com.fixflow.shared.infrastructure.outbox;

import br.com.fixflow.shared.application.outbox.OutboxEvent;
import br.com.fixflow.shared.infrastructure.tenant.TenantScope;
import br.com.fixflow.shared.infrastructure.tenant.TenantTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TenantTransaction tenantTransaction;
    private final TransactionTemplate transaction;

    public OutboxPublisher(JdbcTemplate jdbc, ApplicationEventPublisher events,
                           TenantTransaction tenantTransaction, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.events = events;
        this.tenantTransaction = tenantTransaction;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${fixflow.outbox.poll-delay-ms:1000}")
    public void publishPending() {
        for (UUID tenant : jdbc.queryForList("SELECT * FROM public.pending_outbox_tenants()", UUID.class)) {
            try {
                TenantScope.run(tenant, () -> {
                    for (int i = 0; i < 100; i++) {
                        if (!Boolean.TRUE.equals(transaction.execute(status -> publishOne()))) {
                            break;
                        }
                    }
                });
            } catch (RuntimeException exception) {
                LOG.warn("Outbox transaction failed for tenant {} ({})", tenant, exception.getClass().getSimpleName());
            }
        }
    }

    private boolean publishOne() {
        tenantTransaction.configureCurrentTenant();
        var pending = jdbc.query("""
                SELECT * FROM public.outbox_event
                WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()
                ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Pending(new OutboxEvent(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("created_at").toInstant()), rs.getInt("attempts")));
        if (pending.isEmpty()) {
            return false;
        }
        Pending item = pending.getFirst();
        int attempts = item.attempts() + 1;
        try {
            // Isolate consumer failures, including aborted SQL transactions, from retry bookkeeping.
            transaction.executeWithoutResult(status -> {
                tenantTransaction.configureCurrentTenant();
                events.publishEvent(item.event());
            });
        } catch (Exception exception) {
            jdbc.update("""
                    UPDATE public.outbox_event SET attempts = ?, status = ?, last_error = ?,
                        next_attempt_at = clock_timestamp() + (? * interval '1 second') WHERE id = ?
                    """, attempts, attempts >= 5 ? "FAILED" : "PENDING", exception.getClass().getSimpleName(),
                    Math.min(300, 1L << Math.min(attempts - 1, 8)), item.event().id());
            return true;
        }
        jdbc.update("""
                UPDATE public.outbox_event SET attempts = ?, status = 'PROCESSED',
                    processed_at = clock_timestamp(), last_error = NULL WHERE id = ?
                """, attempts, item.event().id());
        return true;
    }

    private record Pending(OutboxEvent event, int attempts) {}

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "fixflow.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfiguration {}
}
