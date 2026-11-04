package br.com.fixflow.modules.identity.application.usecase;
import br.com.fixflow.modules.identity.application.dto.ManagedUser;
import java.util.List;
import java.util.UUID;
public interface ManageUsers {
    List<ManagedUser> list(UUID tenant);
    ManagedUser create(UUID tenant, String name, String email, String password, String role);
    void change(UUID tenant, UUID user, String role, boolean deactivate);
}
