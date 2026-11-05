package br.com.fixflow.modules.identity.domain;

import java.util.Set;

public final class RolePermissions {
    private RolePermissions() { }
    public static Set<String> forRole(String role) {
        return switch (role) {
            case "OWNER" -> Set.of("tenant:manage", "user:manage", "customer:read", "customer:write", "customer:privacy",
                    "equipment:read", "equipment:write", "equipment:write_limited", "workorder:create",
                    "workorder:read", "workorder:read_assigned", "workorder:assign", "workorder:diagnose",
                    "workorder:execute", "quote:create", "quote:draft", "quote:send", "quote:manual_approve",
                    "inventory:read", "inventory:write", "payment:register", "finance:read",
                    "dashboard:financial", "audit:read");
            case "ATTENDANT" -> Set.of("customer:read", "customer:write", "equipment:read", "equipment:write", "equipment:write_limited",
                    "workorder:create", "workorder:read", "workorder:assign", "quote:create", "quote:draft", "quote:send",
                    "quote:manual_approve", "inventory:read", "payment:register");
            case "TECHNICIAN" -> Set.of("customer:read", "equipment:read", "equipment:write_limited",
                    "workorder:read_assigned", "workorder:diagnose", "workorder:execute", "quote:draft", "inventory:read");
            default -> Set.of();
        };
    }
}
