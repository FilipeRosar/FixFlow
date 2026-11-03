package br.com.fixflow.modules.identity.infrastructure.mail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SmtpResetMailTest {
    @Test
    void sendsSingleUseLinkWithConfiguredOriginAndNoPassword() {
        var sender = mock(JavaMailSender.class);
        var mail = new SmtpResetMail(sender, "noreply@example.invalid", "https://fixflow.example/");
        String token = "A".repeat(43);
        mail.send("owner@example.invalid", token);
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertArrayEquals(new String[]{"owner@example.invalid"}, message.getValue().getTo());
        assertEquals("noreply@example.invalid", message.getValue().getFrom());
        assertTrue(message.getValue().getText().contains("https://fixflow.example/redefinir-senha#token=" + token));
        assertTrue(message.getValue().getText().contains("30 minutos"));
        assertFalse(message.getValue().getText().contains("?token="));
    }
}
