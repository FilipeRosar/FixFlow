package br.com.fixflow.modules.subscriptions;

import br.com.fixflow.modules.subscriptions.domain.SubscriptionAccess;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionAccessTest {
    private final Instant end=Instant.parse("2026-11-10T12:00:00Z");
    @Test void paidPlansRequireFirstPaymentEvenWithFutureTrialDates() {
        for (String plan : java.util.List.of("PROFESSIONAL", "BUSINESS")) {
            var pending = SubscriptionAccess.evaluate(end.minusSeconds(60),end,null,null,plan);
            assertEquals("PAYMENT_REQUIRED",pending.status());
            assertFalse(pending.writable());
            assertEquals(0,pending.trialDaysRemaining());
            assertEquals("ACTIVE",SubscriptionAccess.evaluate(end.minusSeconds(60),end,end,null,plan).status());
        }
        assertEquals("TRIALING",SubscriptionAccess.evaluate(end.minusSeconds(60),end,null,null,"STARTER").status());
    }
    @Test void trialExpiresAtExactBoundary() {
        assertTrue(SubscriptionAccess.evaluate(end.minusSeconds(1),end,null,null).writable());
        assertEquals(1,SubscriptionAccess.evaluate(end.minusSeconds(1),end,null,null).trialDaysRemaining());
        assertEquals("EXPIRED",SubscriptionAccess.evaluate(end,end,null,null).status());
        assertFalse(SubscriptionAccess.evaluate(end,end,null,null).writable());
    }
    @Test void cancelPreservesPaidAccessAndNeverRestartsTrial() {
        assertTrue(SubscriptionAccess.evaluate(end.minusSeconds(1),end.plusSeconds(10000),end,end.minusSeconds(100)).writable());
        assertEquals("CANCELED",SubscriptionAccess.evaluate(end,end.plusSeconds(10000),end,end.minusSeconds(100)).status());
        assertFalse(SubscriptionAccess.evaluate(end,end.plusSeconds(10000),end,null).writable());
        assertEquals("PAST_DUE",SubscriptionAccess.evaluate(end,end.plusSeconds(10000),end,null).status());
    }
}
