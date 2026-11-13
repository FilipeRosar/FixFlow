package br.com.fixflow.modules.subscriptions.infrastructure;

import br.com.fixflow.shared.application.outbox.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Component
public class SubscriptionEmailListener {
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final String from;
    private final String origin;
    public SubscriptionEmailListener(JdbcTemplate jdbc,JavaMailSender mail,@Value("${fixflow.mail.from:noreply@fixflow.local}") String from,
            @Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String origin) {
        this.jdbc=jdbc; this.mail=mail; this.from=from; this.origin=origin.replaceAll("/+$","");
    }
    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void on(OutboxEvent event) {
        if(!event.aggregateType().equals("SUBSCRIPTION")||!Set.of("TrialStarted","TrialEnding","SubscriptionDueSoon","SubscriptionAccessChanged", "SubscriptionActivated","SubscriptionCanceled").contains(event.eventType())) return;
        var recipients=jdbc.queryForList("SELECT * FROM public.saas_owner_emails()",String.class);
        if(recipients.isEmpty()) return;
        var message=new SimpleMailMessage(); message.setFrom(from); message.setTo(recipients.toArray(String[]::new));
        message.setSubject("Sua assinatura FixFlow foi atualizada");
        String text=switch(event.eventType()) {
            case "TrialStarted" -> "Seu teste de 14 dias no FixFlow começou. Nenhum cartão é necessário.";
            case "TrialEnding" -> "Seu período de teste termina em até 3 dias. Consulte os planos para continuar usando o FixFlow.";
            case "SubscriptionDueSoon" -> "Seu período pago termina em até 3 dias. Consulte as cobranças para renovar sua assinatura.";
            case "SubscriptionActivated" -> "Seu pagamento foi confirmado e o acesso ao FixFlow foi liberado.";
            case "SubscriptionCanceled" -> "A renovação foi cancelada. O acesso já contratado permanece até o fim do período vigente.";
            default -> "A situação de acesso da sua assinatura mudou. Seus dados continuam preservados para leitura.";
        };
        message.setText(text+"\n\nConsulte plano, datas e cobranças:\n"+origin+"/inicio/configuracoes/assinatura");
        mail.send(message);
    }
}
