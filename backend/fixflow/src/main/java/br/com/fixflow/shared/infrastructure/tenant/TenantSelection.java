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
    private final br.com.fixflow.modules.tenants.application.FileStorage files;
    public TenantSelection(AuthorizedTenantOperations tenants, JdbcTemplate jdbc, br.com.fixflow.modules.tenants.application.FileStorage files) {
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.files = files;
    }
    @Override
    public SelectedTenant select(UUID tenantId) {
        return tenants.execute(tenantId, member -> new SelectedTenant(member.userId(), member.tenantId(), member.role(),
                jdbc.queryForObject("SELECT name FROM public.tenants WHERE id = ?", String.class, member.tenantId()),
                files.logoDataUrl(member.tenantId(), jdbc.query("SELECT logo_key FROM public.tenant_settings WHERE tenant_id = ?",
                        rs -> rs.next() ? rs.getString("logo_key") : null, member.tenantId()))));
    }
}
