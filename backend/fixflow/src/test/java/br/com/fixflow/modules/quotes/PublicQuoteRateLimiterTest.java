package br.com.fixflow.modules.quotes;

import br.com.fixflow.modules.quotes.infrastructure.PublicQuoteRateLimiter;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicQuoteRateLimiterTest {
    static class MutableClock extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    @Test void exactSlidingWindowsDoNotResetAtTheMinuteBoundary() {
        var clock = new MutableClock(); clock.now = 59000;
        var limiter = new PublicQuoteRateLimiter(clock, 100);
        for (int i = 0; i < 20; i++) assertTrue(limiter.acquire("ip", "token").allowed());
        clock.now = 60000;
        assertFalse(limiter.acquire("ip", "token").allowed());
        assertEquals(59, limiter.acquire("ip", "token").retryAfter());
        clock.now = 119000; assertTrue(limiter.acquire("ip", "token").allowed());
    }
    @Test void tokenLimitSurvivesIpChangesAndExpiresAfterAnHour() {
        var clock = new MutableClock(); var limiter = new PublicQuoteRateLimiter(clock, 100);
        for (int i = 0; i < 60; i++) assertTrue(limiter.acquire("ip" + i, "token").allowed());
        assertEquals(3600, limiter.acquire("another-ip", "token").retryAfter());
        clock.now = 3600000; assertTrue(limiter.acquire("another-ip", "token").allowed());
    }
    @Test void memoryCapacityFailsClosedWithoutEvictingAnActiveLimit() {
        var clock = new MutableClock(); var limiter = new PublicQuoteRateLimiter(clock, 2);
        assertTrue(limiter.acquire("first", "one").allowed()); assertTrue(limiter.acquire("second", "two").allowed());
        assertFalse(limiter.acquire("third", "three").allowed());
        for (int i = 1; i < 20; i++) assertTrue(limiter.acquire("first", "one").allowed());
        assertFalse(limiter.acquire("first", "one").allowed());
        clock.now = 3600000; assertTrue(limiter.acquire("third", "three").allowed());
    }
    @Test void concurrentRequestsCannotExceedTheIpLimit() throws Exception {
        var limiter = new PublicQuoteRateLimiter(new MutableClock(), 100);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 40; i++) calls.add(pool.submit(() -> limiter.acquire("same-ip", null).allowed()));
            int allowed = 0; for (var call : calls) if (call.get()) allowed++;
            assertEquals(20, allowed);
        }
    }
}
