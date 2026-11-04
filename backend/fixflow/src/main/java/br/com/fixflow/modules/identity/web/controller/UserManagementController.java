package br.com.fixflow.modules.identity.web.controller;
import br.com.fixflow.modules.identity.application.usecase.ManageUsers;
import br.com.fixflow.modules.identity.application.dto.ManagedUser;
import br.com.fixflow.modules.identity.web.dto.CreateUserRequest;
import br.com.fixflow.modules.identity.web.dto.ChangeRoleRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.UUID;
@RestController
@RequestMapping("/api/oficinas/{tenant}/usuarios")
public class UserManagementController {

    private final ManageUsers users;

    public UserManagementController(ManageUsers users) { this.users = users; }

    @GetMapping
    public List<ManagedUser> list(@PathVariable UUID tenant) {
        return users.list(tenant);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManagedUser create(@PathVariable UUID tenant, @Valid @RequestBody CreateUserRequest request) {
        return users.create(tenant, request.fullName(), request.email(), request.password(), request.role());
    }

    @PatchMapping("/{user}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@PathVariable UUID tenant, @PathVariable UUID user, @Valid @RequestBody ChangeRoleRequest request) {
        users.change(tenant, user, request.role(), false);
    }
    @DeleteMapping("/{user}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID tenant, @PathVariable UUID user) {
        users.change(tenant, user, null, true);
    }
}
