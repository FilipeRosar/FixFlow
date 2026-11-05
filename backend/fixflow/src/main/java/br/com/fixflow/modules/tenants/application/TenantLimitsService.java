package br.com.fixflow.modules.tenants.application;

import java.util.UUID;
import java.util.function.Supplier;

public interface TenantLimitsService {
    /** Runs OS creation and quota consumption atomically under an authorized tenant. */
    <T> T createWorkOrder(UUID tenant, Supplier<T> creation);
}
