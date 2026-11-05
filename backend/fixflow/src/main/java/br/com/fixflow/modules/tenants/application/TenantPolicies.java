package br.com.fixflow.modules.tenants.application;
import br.com.fixflow.modules.tenants.domain.QuoteDefaults;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;
/** Resolves current persisted settings under the caller's authorized tenant. */
public interface TenantPolicies {
    QuoteDefaults quoteDefaults(UUID tenantId, Instant issuedAt);
    BigDecimal remainingStock(UUID tenantId, BigDecimal balance, BigDecimal quantity);
}
