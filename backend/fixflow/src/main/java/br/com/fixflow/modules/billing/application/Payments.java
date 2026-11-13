package br.com.fixflow.modules.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Payments {
    record Payment(UUID id,
                   UUID workOrderId,
                   BigDecimal amount,
                   String method,
                   String status,
                   Instant paidAt,
                   String notes, String voidReason, Instant voidedAt, UUID voidedBy) { }
    record Registration(Payment payment, boolean replayed) { }
    Registration register(UUID order, String key, BigDecimal amount, String method, Instant paidAt, String notes);
    Payment voidPayment(UUID payment, String reason);
    List<Payment> list(UUID order);
}
