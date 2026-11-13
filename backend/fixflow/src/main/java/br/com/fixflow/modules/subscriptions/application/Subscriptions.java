package br.com.fixflow.modules.subscriptions.application;

import br.com.fixflow.modules.subscriptions.domain.SubscriptionAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface Subscriptions {
    record Plan(String code, String name, BigDecimal monthlyPrice, Integer maxUsers, Integer maxOrders, boolean available) { }
    record Contract(UUID id, String plan, BigDecimal amount, String cycle, String state, Instant cancelledAt, String checkoutUrl) { }
    record Invoice(String id, BigDecimal amount, LocalDate dueDate, String status, String url, Instant paidAt, Instant appliedAt) { }
    record View(SubscriptionAccess access, Instant trialStartedAt, Instant trialEndsAt, String currentPlan,
                Instant paidFrom, Instant paidUntil, boolean cancelAtPeriodEnd, Contract contract,
                long activeUsers, long ordersThisMonth, List<Plan> plans, List<Invoice> invoices, boolean gatewayAvailable) { }
    SubscriptionAccess access();
    View load();
    View changeTrialPlan(String plan);
    View subscribe(String plan, String key);
    View refresh();
    View cancel();
    void mercadoPagoNotification(String type, String resourceId);
    void webhook(String token, String eventId, String event, String paymentId);
}
