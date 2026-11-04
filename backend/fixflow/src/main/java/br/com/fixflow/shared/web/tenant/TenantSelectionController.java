package br.com.fixflow.shared.web.tenant;

import br.com.fixflow.shared.application.tenant.SelectedTenant;
import br.com.fixflow.shared.application.tenant.SelectTenant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/oficinas")
public class TenantSelectionController {
    private final SelectTenant tenants;

    public TenantSelectionController(SelectTenant tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/{tenantId}/selecao")
    public SelectedTenant select(@PathVariable UUID tenantId) {
        return tenants.select(tenantId);
    }
}
