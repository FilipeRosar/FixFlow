package br.com.fixflow.modules.subscriptions.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name="fixflow.subscriptions.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class SubscriptionObservationJob {
    private final JdbcTemplate jdbc;
    public SubscriptionObservationJob(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${fixflow.subscriptions.observation-delay-ms:3600000}")
    @Transactional
    public void observe() { jdbc.queryForObject("SELECT public.observe_saas_subscriptions()",Object.class); }
}
