package br.com.fixflow.modules.identity.infrastructure.mail;
import br.com.fixflow.modules.identity.application.port.ResetMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
@Component
public class SmtpResetMail implements ResetMail {
    private final JavaMailSender sender;
    private final String from;
    private final String origin;
    public SmtpResetMail(JavaMailSender sender, @Value("${fixflow.mail.from:noreply@fixflow.local}") String from,
                         @Value("${fixflow.auth.allowed-origin:http://localhost:4200}") String origin) {
        this.sender = sender; this.from = from; this.origin = origin.replaceAll("/+$", "");
    }
    public void send(String email, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("Redefina sua senha no FixFlow");
        message.setText("Recebemos um pedido para redefinir sua senha.\n\n" + origin + "/redefinir-senha#token=" + token
                + "\n\nEste link é de uso único e expira em 30 minutos. Se você não solicitou a alteração, ignore este e-mail.");
        sender.send(message);
    }
}
