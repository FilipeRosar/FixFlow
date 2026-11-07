package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.QuoteLinkProtection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;

/** Shared recipient validation and encrypted payload for initial send and link rotation. */
@Component
public class QuoteLinkEmail {
    private final JdbcTemplate jdbc;
    private final QuoteLinkProtection links;
    public QuoteLinkEmail(JdbcTemplate jdbc, QuoteLinkProtection links) { this.jdbc = jdbc; this.links = links; }
    String seal(UUID tenant, UUID order, UUID quote, String token, Instant until) {
        var recipient = jdbc.queryForMap("""
                SELECT c.name AS customer_name, t.name AS company_name, w.number, q.version,
                    (SELECT value FROM public.customer_contacts cc WHERE cc.tenant_id = c.tenant_id AND cc.customer_id = c.id
                        AND cc.type = 'EMAIL' AND c.deleted_at IS NULL ORDER BY cc.position, cc.id LIMIT 1) AS email
                FROM public.work_orders w JOIN public.customers c ON c.tenant_id = w.tenant_id AND c.id = w.customer_id
                JOIN public.tenants t ON t.id = w.tenant_id
                JOIN public.quotes q ON q.tenant_id = w.tenant_id AND q.id = ? AND q.work_order_id = w.id
                WHERE w.tenant_id = ? AND w.id = ?
                """, quote, tenant, order);
        String email = (String) recipient.get("email");
        try {
            if (email == null || email.contains("\r") || email.contains("\n")) throw new IllegalArgumentException();
            var address = new jakarta.mail.internet.InternetAddress(email, true); address.validate();
            if (!email.contains("@")) throw new IllegalArgumentException();
        } catch (jakarta.mail.internet.AddressException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cadastre um e-mail válido para o cliente antes de enviar o orçamento.");
        }
        return links.seal(new QuoteLinkProtection.Notification(email, (String) recipient.get("company_name"), (String) recipient.get("customer_name"),
                ((Number) recipient.get("number")).longValue(), ((Number) recipient.get("version")).intValue(), token, until), tenant, quote);
    }
}
