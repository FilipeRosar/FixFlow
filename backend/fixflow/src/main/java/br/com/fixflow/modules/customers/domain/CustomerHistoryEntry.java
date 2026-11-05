package br.com.fixflow.modules.customers.domain;

import java.time.Instant;
import java.util.UUID;

public record CustomerHistoryEntry(UUID id, String type, Instant createdAt, String title,
        String status, String serialNumber, boolean deleted) { }
