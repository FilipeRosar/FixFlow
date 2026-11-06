package br.com.fixflow.modules.quotes.infrastructure;

import br.com.fixflow.modules.quotes.application.QuoteLinkProtection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;

@Component
public class AesQuoteLinkProtection implements QuoteLinkProtection {
    private final byte[] key;
    private final ObjectMapper mapper;
    public AesQuoteLinkProtection(@Value("${fixflow.quotes.link-encryption-key:}") String key, ObjectMapper mapper) {
        this.mapper = mapper;
        try { this.key = key.isBlank() ? new byte[0] : Base64.getDecoder().decode(key); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Chave de proteção de links inválida."); }
        if (this.key.length != 0 && this.key.length != 32) throw new IllegalArgumentException("Chave de proteção de links deve ter 32 bytes.");
    }
    private Cipher cipher(int mode, byte[] nonce, UUID tenant, UUID quote) throws GeneralSecurityException {
        if (key.length != 32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a proteção dos links de orçamento antes do envio.");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD((tenant + ":" + quote).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    @Override public String seal(Notification notification, UUID tenant, UUID quote) {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, tenant, quote).doFinal(mapper.writeValueAsBytes(notification));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Falha ao proteger o link de orçamento."); }
    }
    @Override public Notification open(String encrypted, UUID tenant, UUID quote) {
        try {
            String[] parts = encrypted.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            byte[] json = cipher(Cipher.DECRYPT_MODE, nonce, tenant, quote).doFinal(Base64.getDecoder().decode(parts[1]));
            return mapper.readValue(json, Notification.class);
        } catch (GeneralSecurityException | IllegalArgumentException exception) { throw new IllegalStateException("Entrega de orçamento inválida."); }
    }
}
