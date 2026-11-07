package br.com.fixflow.modules.quotes.application;

import java.time.Instant;
import java.util.UUID;

public interface QuoteLinks {
    record Receipt(UUID workOrderId, UUID quoteId, int workOrderVersion, String status, String linkStatus, String notificationStatus, Instant validUntil) { }
    Receipt revoke(UUID order, UUID quote, int version, String idempotencyKey);
    Receipt resend(UUID order, UUID quote, int version, String idempotencyKey);
}
