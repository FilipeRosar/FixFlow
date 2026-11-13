package br.com.fixflow.modules.billing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PixCharges {
    record Charge(UUID id, UUID workOrderId, BigDecimal amount, LocalDate dueDate, String status,
                  String gatewayRef, String payload, String encodedImage, String expirationDate) { }
    Charge create(UUID order, String key, String customer, BigDecimal amount, LocalDate dueDate);
    Charge refresh(UUID order, UUID charge);
    List<Charge> list(UUID order);
    void webhook(String token, String eventId, String event, String paymentId);
}
