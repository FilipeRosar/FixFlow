package br.com.fixflow.shared.infrastructure.tenant;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.SelectTenant;
import br.com.fixflow.shared.application.tenant.SelectedTenant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service
public class TenantSelection implements SelectTenant {
    private final AuthorizedTenantOperations tenants;
    private final JdbcTemplate jdbc;
    public TenantSelection(AuthorizedTenantOperations tenants, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.jdbc = jdbc;
    }
    @Override
    public SelectedTenant select(UUID tenantId) {
        return tenants.execute(tenantId, member -> new SelectedTenant(member.userId(), member.tenantId(), member.role(),
                jdbc.queryForObject("SELECT name FROM public.tenants WHERE id = ?", String.class, member.tenantId())));
    }
}
