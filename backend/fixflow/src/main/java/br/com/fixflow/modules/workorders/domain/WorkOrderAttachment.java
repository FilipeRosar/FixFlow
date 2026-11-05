package br.com.fixflow.modules.workorders.domain;

import java.time.Instant;
import java.util.UUID;

public record WorkOrderAttachment(UUID id, String originalName, String contentType, long sizeBytes, String checksum,
                                  UUID uploadedBy, Instant createdAt) { }
