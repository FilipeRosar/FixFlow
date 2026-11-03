package br.com.fixflow.modules.identity.infrastructure.configuration;
import br.com.fixflow.modules.identity.application.port.*;
import br.com.fixflow.modules.identity.application.usecase.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration(proxyBeanMethods = false)
public class AccountRecoveryConfiguration {
    @Bean
    AccountRecovery accountRecovery(AccountRecoveryStore recovery, SessionStore sessions, RefreshSecrets secrets, PasswordHasher passwords, ResetMail mail) {
        return new RecoverAccount(recovery, sessions, secrets, passwords, mail);
    }
    @Bean
    ThreadPoolTaskExecutor passwordRecoveryExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("password-recovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
