package br.com.fixflow.modules.billing.infrastructure;

import br.com.fixflow.shared.application.tenant.AuthorizedTenantOperations;
import br.com.fixflow.shared.infrastructure.security.CurrentAuthenticatedIdentity;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class JdbcFinance implements br.com.fixflow.modules.billing.application.Finance {
    private final JdbcTemplate jdbc;
    private final AuthorizedTenantOperations tenants;
    private final CurrentAuthenticatedIdentity identity;

    public JdbcFinance(JdbcTemplate jdbc, AuthorizedTenantOperations tenants, CurrentAuthenticatedIdentity identity) {
        this.jdbc = jdbc; this.tenants = tenants; this.identity = identity;
    }
    public Report report(Instant from, Instant to, String status, String method, int page) {
        var tenant = identity.require().tenantId();
        if (tenant == null) throw new AccessDeniedException("Selecione uma empresa.");
        return tenants.execute(tenant, m -> {
            if (!m.role().name().equals("OWNER")) throw new AccessDeniedException("Financeiro exclusivo do proprietário.");
            var start = Timestamp.from(from);
            var end = Timestamp.from(to);
            var summary = jdbc.queryForObject("""
                SELECT coalesce(sum(amount) FILTER (WHERE status = 'CONFIRMED'), 0) received,
                       coalesce(sum(amount) FILTER (WHERE status = 'VOIDED'), 0) voided,
                       count(*) FILTER (WHERE status = 'CONFIRMED') confirmed_count
                FROM public.payments WHERE tenant_id = ? AND paid_at >= ? AND paid_at < ?
                """, (rs, n) -> new Summary(rs.getBigDecimal("received"), rs.getBigDecimal("voided"),
                    rs.getLong("confirmed_count")), m.tenantId(), start, end);
            String where = " WHERE p.tenant_id = ? AND p.paid_at >= ? AND p.paid_at < ? AND (? = '' OR p.status = ?) AND (? = '' OR p.method = ?)";
            Object[] args = {m.tenantId(), start, end, status, status, method, method};
            long total = jdbc.queryForObject("SELECT count(*) FROM public.payments p" + where, Long.class, args);
            var parameters = new java.util.ArrayList<Object>(java.util.Arrays.asList(args));
            parameters.add((long) page * 20);
            var rows = jdbc.query("""
                SELECT p.*, w.number, c.name customer_name FROM public.payments p
                JOIN public.work_orders w ON w.tenant_id = p.tenant_id AND w.id = p.work_order_id
                JOIN public.customers c ON c.tenant_id = w.tenant_id AND c.id = w.customer_id
                """ + where + " ORDER BY p.paid_at DESC, p.id DESC LIMIT 20 OFFSET ?",
                (rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("work_order_id", UUID.class),
                    rs.getLong("number"), rs.getString("customer_name"), rs.getBigDecimal("amount"),
                    rs.getString("method"), rs.getString("status"), rs.getTimestamp("paid_at").toInstant()), parameters.toArray());
            return new Report(summary, rows, total);
        });
    }
}
