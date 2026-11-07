package br.com.fixflow.modules.notifications.infrastructure;

import br.com.fixflow.modules.quotes.application.QuoteLinkProtection;
import br.com.fixflow.shared.application.outbox.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class QuoteSentEmailListener {
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final QuoteLinkProtection links;
    private final ObjectMapper mapper;
    private final String origin;
    private final String from;
    public QuoteSentEmailListener(JdbcTemplate jdbc, JavaMailSender mail, QuoteLinkProtection links, ObjectMapper mapper,
            @Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String origin, @Value("${fixflow.mail.from:noreply@fixflow.local}") String from) {
        this.jdbc = jdbc; this.mail = mail; this.links = links; this.mapper = mapper;
        this.origin = origin.replaceAll("/+$", ""); this.from = from;
    }
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(OutboxEvent event) {
        if (!event.eventType().equals("QuoteSent") && !event.eventType().equals("QuoteLinkResent")) return;
        var payload = mapper.readTree(event.payload());
        if (!payload.hasNonNull("encryptedNotification")) return; // No invented delivery for legacy projections.
        UUID quote = UUID.fromString(payload.get("quoteId").asText());
        jdbc.update("INSERT INTO public.quote_email_deliveries(tenant_id, event_id, status) VALUES (?, ?, 'SENDING') ON CONFLICT DO NOTHING", event.tenantId(), event.id());
        String status = jdbc.queryForObject("SELECT status FROM public.quote_email_deliveries WHERE tenant_id = ? AND event_id = ? FOR UPDATE", String.class, event.tenantId(), event.id());
        if (!"SENDING".equals(status)) return;
        var notification = links.open(payload.get("encryptedNotification").asText(), event.tenantId(), quote);
        boolean readOnly = payload.hasNonNull("readOnly") && payload.get("readOnly").asBoolean();
        String tokenHash;
        try { tokenHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(notification.token().getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM public.quote_access_tokens a JOIN public.quotes q ON q.tenant_id = a.tenant_id AND q.id = a.quote_id
                JOIN public.tenants t ON t.id = q.tenant_id AND t.status = 'ACTIVE'
                JOIN public.work_order_transition_facts f ON f.tenant_id = q.tenant_id AND f.work_order_id = q.work_order_id AND f.quote_id = q.id
                WHERE a.tenant_id = ? AND a.quote_id = ? AND q.work_order_id = ? AND a.token_hash = ? AND a.revoked_at IS NULL
                    AND ((? AND q.status IN ('APPROVED', 'REJECTED') AND a.read_until > clock_timestamp())
                        OR (NOT ? AND q.status = 'SENT' AND a.expires_at > clock_timestamp() AND f.quote_token_hash = a.token_hash)))
                """, Boolean.class, event.tenantId(), quote, event.aggregateId(), tokenHash, readOnly, readOnly));
        if (!active) {
            jdbc.update("UPDATE public.quote_email_deliveries SET status = 'SKIPPED' WHERE tenant_id = ? AND event_id = ?", event.tenantId(), event.id()); return;
        }
        var message = new SimpleMailMessage(); message.setFrom(from); message.setTo(notification.email());
        message.setSubject((readOnly ? "Consulta do orçamento da OS nº " : "Orçamento da OS nº ") + notification.orderNumber() + " — " + notification.companyName());
        String until = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo")).format(notification.validUntil());
        message.setText("Olá, " + notification.customerName() + ".\n\n" + notification.companyName() + " enviou o orçamento da OS nº " + notification.orderNumber()
                + " (versão " + notification.quoteVersion() + ").\n\nConsulte a proposta pelo link:\n" + origin + "/orcamento#token=" + notification.token()
                + "\n\n" + (readOnly ? "Link de consulta disponível até " : "Válido até ") + until + " (horário de Brasília).\nEste link é pessoal. Não o compartilhe.\n"
                + (readOnly ? "A decisão já registrada foi preservada. Este link permite somente consulta." : "A abertura do link não aprova o orçamento."));
        mail.send(message);
        jdbc.update("UPDATE public.quote_email_deliveries SET status = 'SENT', sent_at = clock_timestamp() WHERE tenant_id = ? AND event_id = ?", event.tenantId(), event.id());
    }
}
