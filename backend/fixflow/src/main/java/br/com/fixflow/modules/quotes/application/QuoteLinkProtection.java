package br.com.fixflow.modules.quotes.application;

import java.time.Instant;
import java.util.UUID;

/** Only encrypted delivery material may be persisted alongside a token hash. */
public interface QuoteLinkProtection {
    record Notification(String email, String companyName, String customerName, long orderNumber, int quoteVersion, String token, Instant validUntil) { }
    String seal(Notification notification, UUID tenant, UUID quote);
    Notification open(String encrypted, UUID tenant, UUID quote);
}
