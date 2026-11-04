package br.com.fixflow.modules.identity.infrastructure.persistence;

import br.com.fixflow.modules.identity.application.dto.ManagedUser;
import br.com.fixflow.modules.identity.application.usecase.ManageUsers;
import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.application.tenant.AuthorizedMembership;
import br.com.fixflow.shared.application.audit.AuditService;
import br.com.fixflow.shared.application.registration.EmailAlreadyRegisteredException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class JdbcManageUsers implements ManageUsers {
    private final AuthorizedTenantOperations tenants;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    public JdbcManageUsers(AuthorizedTenantOperations tenants, JdbcTemplate jdbc, PasswordEncoder passwords, AuditService audit) {
        this.tenants = tenants; this.jdbc = jdbc; this.passwords = passwords; this.audit = audit;
    }
    private void requireOwner(AuthorizedMembership member) {
        if (!br.com.fixflow.modules.identity.domain.RolePermissions.forRole(member.role().name()).contains("user:manage")) throw new AccessDeniedException("Owner obrigatório.");
    }
    @Override
    public List<ManagedUser> list(UUID tenant) {
        return tenants.execute(tenant, member -> {
            requireOwner(member);
            return jdbc.query("SELECT * FROM public.manage_tenant_users(?, ?, NULL, NULL, NULL, NULL, NULL, false)",
                    (rs, row) -> new ManagedUser(rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                            rs.getString("email"), rs.getString("role"), rs.getString("status")), member.userId(), tenant);
        });
    }
    @Override
    public ManagedUser create(UUID tenant, String name, String email, String password, String role) {
        return tenants.execute(tenant, member -> {
            requireOwner(member);
            if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Senha excede 72 bytes.");
            try {
                var user = jdbc.queryForObject("SELECT * FROM public.manage_tenant_users(?, ?, NULL, ?, ?, ?, ?, false)",
                        (rs, row) -> new ManagedUser(rs.getObject("user_id", UUID.class), rs.getString("full_name"),
                                rs.getString("email"), rs.getString("role"), rs.getString("status")),
                        member.userId(), tenant, name.strip(), email.strip().toLowerCase(Locale.ROOT), passwords.encode(password), role);
                audit.record(member.userId(), "USER_CREATED", "USER", user.userId(), "{}");
                return user;
            } catch (DuplicateKeyException exception) { throw new EmailAlreadyRegisteredException(); }
        });
    }
    @Override
    public void change(UUID tenant, UUID user, String role, boolean deactivate) {
        tenants.execute(tenant, member -> {
            requireOwner(member);
            java.util.List<java.util.Map<String, Object>> results;
            try {
                results = jdbc.queryForList("SELECT * FROM public.manage_tenant_users(?, ?, ?, NULL, NULL, NULL, ?, ?)",
                    member.userId(), tenant, user, role, deactivate);
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                if (exception.getMostSpecificCause() instanceof java.sql.SQLException sql && "23514".equals(sql.getSQLState()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "A oficina precisa de um Owner ativo.");
                throw exception;
            }
            if (results.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado.");
            audit.record(member.userId(), deactivate ? "USER_DEACTIVATED" : "USER_ROLE_CHANGED", "USER", user,
                    role == null ? "{}" : "{\"role\":\"" + role + "\"}");
            return null;
        });
    }
}
