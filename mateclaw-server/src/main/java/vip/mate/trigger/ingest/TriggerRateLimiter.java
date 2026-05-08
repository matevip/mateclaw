package vip.mate.trigger.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-trigger sliding-window rate limiter. Each {@code triggerId} keeps a
 * 60-second window of fire timestamps; an event is allowed iff fewer than
 * the trigger's {@code rate_limit_per_min} entries already live in the
 * window. The window is local to this node — for a multi-node deployment
 * the cap is a per-node bound, not a global one. v0 accepts that trade
 * because the alternative (DB-backed counters) costs a round-trip on every
 * event and event volumes are well below the cap in practice.
 */
public class TriggerRateLimiter {

    private final Map<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Duration windowSize;

    public TriggerRateLimiter() {
        this(Duration.ofMinutes(1));
    }

    TriggerRateLimiter(Duration windowSize) {
        this.windowSize = windowSize;
    }

    /**
     * Try to admit an event for {@code triggerId} at {@code now}. Returns
     * {@code true} when the event fits under {@code limitPerMin}; {@code false}
     * when the window is full. The window is purged of expired entries first
     * so a long-idle trigger reverts to full capacity.
     *
     * <p>{@code limitPerMin <= 0} disables the limiter for that trigger.
     */
    public boolean tryAcquire(long triggerId, int limitPerMin, Instant now) {
        if (limitPerMin <= 0) return true;
        Deque<Instant> window = windows.computeIfAbsent(triggerId, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(windowSize);
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMin) return false;
            window.addLast(now);
            return true;
        }
    }
}
