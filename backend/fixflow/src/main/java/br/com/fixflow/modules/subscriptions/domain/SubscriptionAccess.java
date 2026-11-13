package br.com.fixflow.modules.subscriptions.domain;

import java.time.Instant;

public record SubscriptionAccess(String status, boolean writable, Instant accessUntil, long trialDaysRemaining) {
    public static SubscriptionAccess evaluate(Instant now, Instant trialEnd, Instant paidUntil, Instant cancelledAt, String plan) {
        if (!"STARTER".equals(plan) && paidUntil == null)
            return new SubscriptionAccess("PAYMENT_REQUIRED", false, trialEnd, 0);
        return evaluate(now, trialEnd, paidUntil, cancelledAt);
    }
    public static SubscriptionAccess evaluate(Instant now, Instant trialEnd, Instant paidUntil, Instant cancelledAt) {
        boolean paid = paidUntil != null && now.isBefore(paidUntil);
        boolean trial = paidUntil == null && now.isBefore(trialEnd);
        Instant until = paidUntil == null ? trialEnd : paidUntil;
        String state = paid ? "ACTIVE" : trial ? "TRIALING" : cancelledAt != null ? "CANCELED"
                : paidUntil == null ? "EXPIRED" : "PAST_DUE";
        long days = trial ? Math.max(0, (java.time.Duration.between(now, trialEnd).getSeconds() + 86399) / 86400) : 0;
        return new SubscriptionAccess(state, paid || trial, until, days);
    }
}
