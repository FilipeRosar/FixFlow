package br.com.fixflow.modules.workorders.domain;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record WorkOrderItem(UUID id, String type, UUID partId, String description, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal unitCost, Instant createdAt, Instant removedAt) { }
