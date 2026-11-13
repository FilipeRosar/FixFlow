package br.com.fixflow.shared.infrastructure.registration.usecase;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="fixflow.subscriptions.scheduling-enabled",havingValue="true",matchIfMissing=true)
public class PendingSignupCleanupJob {
    private final JdbcTemplate jdbc;
    public PendingSignupCleanupJob(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Scheduled(fixedDelayString="${fixflow.signup.cleanup-delay-ms:3600000}")
    public void purge(){jdbc.queryForObject("SELECT public.purge_expired_signups()",Object.class);}
}
