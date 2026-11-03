package br.com.fixflow.shared.application.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                          String eventType, String payload, Instant occurredAt) {}
