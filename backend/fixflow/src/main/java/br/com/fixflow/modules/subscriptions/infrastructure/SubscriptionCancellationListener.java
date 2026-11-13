package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.modules.subscriptions.application.SubscriptionGateway;
import br.com.fixflow.shared.application.outbox.OutboxEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Outbox retries a cancellation even if the initiating HTTP request times out. */
@Component
public class SubscriptionCancellationListener {
    private final SubscriptionGateway gateway;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public SubscriptionCancellationListener(SubscriptionGateway gateway,JdbcTemplate jdbc,ObjectMapper mapper) {
        this.gateway=gateway; this.jdbc=jdbc; this.mapper=mapper;
    }
    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void on(OutboxEvent event) {
        if(!event.aggregateType().equals("SUBSCRIPTION")||!event.eventType().equals("SubscriptionCancellationRequested")) return;
        String remote=mapper.readTree(event.payload()).path("gateway").asText();
        gateway.cancel(remote);
        jdbc.queryForObject("SELECT public.complete_saas_cancellation(?,?)",Object.class,event.aggregateId(),remote);
    }
}
