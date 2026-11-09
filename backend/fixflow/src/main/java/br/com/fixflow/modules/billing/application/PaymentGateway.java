package br.com.fixflow.modules.billing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PaymentGateway {
    record PixRequest(UUID reference, String customer, String document, BigDecimal amount, LocalDate dueDate) { }
    record RemotePayment(String id, String externalReference, String customer, String billingType, String status,
                         BigDecimal value, LocalDate paymentDate) { }
    record PixCode(String encodedImage, String payload, String expirationDate) { }
    void requireAvailable();
    void authenticateWebhook(String token);
    RemotePayment createPix(PixRequest request);
    Optional<RemotePayment> findPix(UUID reference);
    RemotePayment retrieve(String id);
    PixCode qrCode(String id);
}
