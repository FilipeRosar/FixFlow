package br.com.fixflow.shared.application.audit;

import java.util.UUID;

public interface AuditService {
    /** Records an event in the caller's transaction and tenant. A null actor denotes a system event.
     * Details must be valid JSON and must not contain passwords, tokens or other secrets.
     */
    UUID record(UUID actorId, String action, String resourceType, UUID resourceId, String details);
}
