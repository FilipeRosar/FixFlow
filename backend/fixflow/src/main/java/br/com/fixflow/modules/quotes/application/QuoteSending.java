package br.com.fixflow.modules.quotes.application;

import java.time.Instant;
import java.util.UUID;

public interface QuoteSending {
    record Receipt(UUID workOrderId, UUID quoteId, int workOrderVersion, String status, String notificationStatus, Instant validUntil) { }
    Receipt send(UUID order, UUID quote, int workOrderVersion, String idempotencyKey);
}
