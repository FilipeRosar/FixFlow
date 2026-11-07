package br.com.fixflow.modules.inventory.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "fixflow.inventory.reconciliation.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class InventoryReconciliationScheduler {
    private final InventoryReconciliationJob job;
    public InventoryReconciliationScheduler(InventoryReconciliationJob job) { this.job = job; }

    @Scheduled(fixedDelayString = "${fixflow.inventory.reconciliation.delay-ms:3600000}",
            initialDelayString = "${fixflow.inventory.reconciliation.initial-delay-ms:60000}")
    public void reconcile() { job.reconcileAll(); }
}
