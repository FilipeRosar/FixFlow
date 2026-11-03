package br.com.fixflow.shared.web.tenant;

import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequestMapping("/api/oficinas")
public class TenantSelectionController {
    private final AuthorizedTenantOperations tenants;

    public TenantSelectionController(AuthorizedTenantOperations tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/{tenantId}/selecao")
    public AuthorizedMembership select(@PathVariable UUID tenantId) {
        return tenants.execute(tenantId, Function.identity());
    }
}
