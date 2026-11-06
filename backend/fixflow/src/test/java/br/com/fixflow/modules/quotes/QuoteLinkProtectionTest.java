package br.com.fixflow.modules.quotes;

import br.com.fixflow.modules.quotes.application.QuoteLinkProtection;
import br.com.fixflow.modules.quotes.infrastructure.AesQuoteLinkProtection;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QuoteLinkProtectionTest {
    final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.json.JsonMapper();
    final QuoteLinkProtection.Notification notification = new QuoteLinkProtection.Notification("ana@example.test", "Empresa", "Ana", 42, 1, "secret-link-token", Instant.parse("2026-11-15T12:00:00Z"));
    @Test void encryptsRandomlyAndBindsTheEnvelopeToItsTenantAndQuote() {
        var protection = new AesQuoteLinkProtection("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", mapper);
        UUID tenant = UUID.randomUUID(), quote = UUID.randomUUID();
        String encrypted = protection.seal(notification, tenant, quote);
        assertFalse(encrypted.contains(notification.token()));
        assertFalse(encrypted.contains(notification.email()));
        assertNotEquals(encrypted, protection.seal(notification, tenant, quote));
        assertEquals(notification, protection.open(encrypted, tenant, quote));
        assertThrows(IllegalStateException.class, () -> protection.open(encrypted, UUID.randomUUID(), quote));
        assertThrows(IllegalStateException.class, () -> protection.open(encrypted, tenant, UUID.randomUUID()));
        String[] parts = encrypted.split("\\.");
        byte[] bytes = java.util.Base64.getDecoder().decode(parts[1]); bytes[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> protection.open(parts[0] + "." + java.util.Base64.getEncoder().encodeToString(bytes), tenant, quote));
    }
    @Test void refusesUnconfiguredOrInvalidKeys() {
        var unconfigured = new AesQuoteLinkProtection("", mapper);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> unconfigured.seal(notification, UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new AesQuoteLinkProtection("wrong key", mapper));
        assertThrows(IllegalArgumentException.class, () -> new AesQuoteLinkProtection("YQ==", mapper));
    }
}
