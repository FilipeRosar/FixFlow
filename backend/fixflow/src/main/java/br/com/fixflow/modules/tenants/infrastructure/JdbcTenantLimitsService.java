package br.com.fixflow.modules.tenants.infrastructure;

import br.com.fixflow.modules.tenants.application.TenantLimitsService;
import br.com.fixflow.modules.tenants.domain.PlanLimitExceededException;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class JdbcTenantLimitsService implements TenantLimitsService {
    private final AuthorizedTenantOperations tenants;
    private final JdbcTemplate jdbc;
    public JdbcTenantLimitsService(AuthorizedTenantOperations tenants, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }
    @Override
    public <T> T createWorkOrder(UUID tenant, Supplier<T> creation) {
        java.util.Objects.requireNonNull(creation);
        return tenants.execute(tenant, member -> {
            if (!br.com.fixflow.modules.identity.domain.RolePermissions.forRole(member.role().name())
                    .contains("workorder:create")) throw new AccessDeniedException("Criação de OS não autorizada.");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT public.consume_order_quota(?)", Boolean.class, tenant)))
                throw new PlanLimitExceededException("OS mensais");
            return creation.get();
        });
    }
}
