package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record WorkOrderTimelineEntry(UUID id, String eventType, UUID actorId, String actorName, Instant occurredAt,
                                     String origin, String fromStatus, String toStatus, String reason, Map<String, Object> details) { }
