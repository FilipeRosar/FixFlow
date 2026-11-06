package br.com.fixflow.modules.quotes.infrastructure;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** Exact sliding windows, bounded memory, and fail-closed admission at capacity. Single-instance MVP. */
@Component
public class PublicQuoteRateLimiter {
    private final Clock clock;
    private final int capacity;
    private final Map<String, ArrayDeque<Long>> ips = new HashMap<>(), tokens = new HashMap<>();
    private long nextCleanup;
    public PublicQuoteRateLimiter() { this(Clock.systemUTC(), 10000); }
    public PublicQuoteRateLimiter(Clock clock, int capacity) { this.clock = clock; this.capacity = capacity; }
    public record Admission(boolean allowed, int retryAfter) { }
    private static void prune(ArrayDeque<Long> queue, long threshold) {
        while (!queue.isEmpty() && queue.peekFirst() <= threshold) queue.removeFirst();
    }
    private static int delay(ArrayDeque<Long> queue, long now, long window, int maximum) {
        if (queue == null) return 0;
        prune(queue, now - window);
        return queue.size() < maximum ? 0 : (int) Math.max(1, (queue.peekFirst() + window - now + 999) / 1000);
    }
    public synchronized Admission acquire(String ipHash, String tokenHash) {
        long now = clock.millis();
        if (now >= nextCleanup) {
            ips.values().removeIf(queue -> { prune(queue, now - 60000); return queue.isEmpty(); });
            tokens.values().removeIf(queue -> { prune(queue, now - 3600000); return queue.isEmpty(); });
            nextCleanup = now + 60000;
        }
        int wait = Math.max(delay(ips.get(ipHash), now, 60000, 20), tokenHash == null ? 0 : delay(tokens.get(tokenHash), now, 3600000, 60));
        if (wait > 0) return new Admission(false, wait);
        if (!ips.containsKey(ipHash) && ips.size() >= capacity) return new Admission(false, 60);
        if (tokenHash != null && !tokens.containsKey(tokenHash) && tokens.size() >= capacity) return new Admission(false, 3600);
        ips.computeIfAbsent(ipHash, key -> new ArrayDeque<>()).addLast(now);
        if (tokenHash != null) tokens.computeIfAbsent(tokenHash, key -> new ArrayDeque<>()).addLast(now);
        return new Admission(true, 0);
    }
}
