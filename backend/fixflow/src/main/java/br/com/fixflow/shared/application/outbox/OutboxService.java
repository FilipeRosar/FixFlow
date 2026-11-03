package br.com.fixflow.shared.application.outbox;

import java.util.UUID;

public interface OutboxService {
    UUID record(String aggregateType, UUID aggregateId, String eventType, String payload);
}
