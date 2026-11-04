package br.com.fixflow.shared.application.tenant;
import java.util.UUID;
public interface SelectTenant {
    SelectedTenant select(UUID tenantId);
}
