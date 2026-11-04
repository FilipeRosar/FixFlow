package br.com.fixflow.modules.identity;
import br.com.fixflow.modules.identity.domain.RolePermissions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RolePermissionsTest {
    @Test void ownerIncludesEveryStaffPermission() {
        var owner = RolePermissions.forRole("OWNER");
        assertTrue(owner.containsAll(RolePermissions.forRole("ATTENDANT")));
        assertTrue(owner.containsAll(RolePermissions.forRole("TECHNICIAN")));
    }
    @Test void technicianIsScopedAndFinancialAccessIsOwnerOnly() {
        var tech = RolePermissions.forRole("TECHNICIAN");
        assertFalse(tech.contains("workorder:create"));
        assertFalse(tech.contains("workorder:read"));
        assertTrue(tech.contains("workorder:read_assigned"));
        assertTrue(tech.contains("quote:draft"));
        assertFalse(tech.contains("quote:send"));
        for (var role : new String[]{"TECHNICIAN", "ATTENDANT"}) {
            assertFalse(RolePermissions.forRole(role).contains("finance:read"));
            assertFalse(RolePermissions.forRole(role).contains("user:manage"));
        }
        assertTrue(RolePermissions.forRole("UNKNOWN").isEmpty());
        assertTrue(RolePermissions.forRole("ADMIN").isEmpty());
    }
}
