package br.com.fixflow.modules.subscriptions.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionGateway {
    /** A definitive provider rejection: no remote subscription was created. */
    class CreationRejected extends org.springframework.web.server.ResponseStatusException {
        public CreationRejected() {
            this("O Mercado Pago recusou a criação da assinatura. Verifique a configuração e tente novamente.");
        }
        public CreationRejected(String detail) {
            super(org.springframework.http.HttpStatus.BAD_GATEWAY, detail);
        }
    }
    record Customer(String id, String reference, String document) { }
    record Recurrence(String id, String reference, String customer, BigDecimal amount, String cycle, String billingType, boolean deleted, String checkoutUrl) {
        public Recurrence(String id, String reference, String customer, BigDecimal amount, String cycle, String billingType, boolean deleted) {
            this(id, reference, customer, amount, cycle, billingType, deleted, null);
        }
    }
    record Payment(String id, String subscription, String customer, BigDecimal amount, LocalDate dueDate,
                   String status, String invoiceUrl, Instant paidAt) { }
    default String provider() { return "asaas"; }
    default boolean requiresDocument() { return true; }
    default String billingType() { return "PIX"; }
    default Recurrence create(UUID contract, String customer, String email, BigDecimal amount, LocalDate firstDueDate) {
        return create(contract, customer, amount, firstDueDate);
    }
    boolean available();
    void requireAvailable();
    void authenticate(String token);
    Optional<Customer> findCustomer(UUID tenant);
    Customer createCustomer(UUID tenant, String name, String document, String email);
    Optional<Recurrence> find(UUID contract);
    Recurrence create(UUID contract, String customer, BigDecimal amount, LocalDate firstDueDate);
    Recurrence retrieve(String id);
    void cancel(String id);
    List<Payment> payments(String subscription);
    Payment payment(String id);
}
