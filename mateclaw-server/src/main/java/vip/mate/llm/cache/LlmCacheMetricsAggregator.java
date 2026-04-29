package vip.mate.llm.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, per-phase aggregator for LLM cache usage.
 *
 * <p>Complements RFC-014 (Anthropic prompt cache wiring) by exposing live
 * observability. RFC-014 already persists daily totals in {@code mate_usage_daily};
 * this aggregator lets operators see the effective cache hit ratio right now,
 * per graph phase (reasoning / step_execution / plan_generation / ...), without
 * needing to query the database or install Prometheus/Actuator.</p>
 *
 * <p>Thread-safe and lock-free: counters are striped per phase via
 * {@link ConcurrentHashMap} and each field is an {@link AtomicLong}.</p>
 *
 * <p>Periodic log summary: every {@value #LOG_EVERY_N_REQUESTS}-th request for a
 * given phase, a one-line summary is emitted at INFO level so long-running
 * processes leave a trail even without a metrics backend.</p>
 *
 * <p>If {@code spring-boot-starter-actuator} is later added, this aggregator
 * can be bridged to a {@code MeterRegistry} by iterating {@link #snapshot()}.</p>
 */
@Slf4j
@Component
public class LlmCacheMetricsAggregator {

    private static final int LOG_EVERY_N_REQUESTS = 50;

    /** Per-phase counters. {@code phase} is a short label like "reasoning" or "step_execution". */
    private final Map<String, PhaseCounters> byPhase = new ConcurrentHashMap<>();

    /**
     * Record a single LLM call's token usage. Safe to call from streaming
     * subscriber threads; does not block.
     */
    public void record(String phase,
                       int promptTokens,
                       int completionTokens,
                       int cacheReadTokens,
                       int cacheWriteTokens) {
        if (phase == null || phase.isEmpty()) {
            phase = "unknown";
        }
        PhaseCounters c = byPhase.computeIfAbsent(phase, p -> new PhaseCounters());
        long requests = c.requests.incrementAndGet();
        c.promptTokens.addAndGet(Math.max(0, promptTokens));
        c.completionTokens.addAndGet(Math.max(0, completionTokens));
        c.cacheReadTokens.addAndGet(Math.max(0, cacheReadTokens));
        c.cacheWriteTokens.addAndGet(Math.max(0, cacheWriteTokens));
        if (cacheReadTokens > 0) {
            c.cacheHits.incrementAndGet();
        } else if (cacheWriteTokens > 0) {
            c.cacheWrites.incrementAndGet();
        } else {
            c.cacheMisses.incrementAndGet();
        }

        if (requests % LOG_EVERY_N_REQUESTS == 0) {
            log.info("[llm-cache] phase={} requests={} hit_ratio={} prompt_tokens={} cache_read={} cache_write={}",
                    phase, requests, formatHitRatio(c),
                    c.promptTokens.get(), c.cacheReadTokens.get(), c.cacheWriteTokens.get());
        }
    }

    /** Point-in-time copy of all counters. Intended for admin endpoints and tests. */
    public Map<String, PhaseSnapshot> snapshot() {
        Map<String, PhaseSnapshot> out = new java.util.LinkedHashMap<>();
        byPhase.forEach((phase, c) -> out.put(phase, c.snapshot()));
        return out;
    }

    /** Reset all counters. Useful for tests; not wired to any endpoint. */
    public void reset() {
        byPhase.clear();
    }

    private static String formatHitRatio(PhaseCounters c) {
        long hits = c.cacheHits.get();
        long total = c.requests.get();
        if (total == 0) return "n/a";
        return String.format("%.1f%%", 100.0 * hits / total);
    }

    /** Mutable per-phase counters. Package-private so the aggregator owns the state. */
    private static final class PhaseCounters {
        final AtomicLong requests = new AtomicLong();
        final AtomicLong cacheHits = new AtomicLong();
        final AtomicLong cacheWrites = new AtomicLong();
        final AtomicLong cacheMisses = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong completionTokens = new AtomicLong();
        final AtomicLong cacheReadTokens = new AtomicLong();
        final AtomicLong cacheWriteTokens = new AtomicLong();

        PhaseSnapshot snapshot() {
            return new PhaseSnapshot(requests.get(), cacheHits.get(), cacheWrites.get(),
                    cacheMisses.get(), promptTokens.get(), completionTokens.get(),
                    cacheReadTokens.get(), cacheWriteTokens.get());
        }
    }

    /**
     * Immutable snapshot of one phase's counters.
     *
     * <p>{@link #hitRatio()} is hits / requests; {@code writes} are counted
     * separately because the first call on a new cache breakpoint is a
     * pure write (no hit) and shouldn't pollute the hit ratio denominator.</p>
     */
    public record PhaseSnapshot(long requests,
                                long cacheHits,
                                long cacheWrites,
                                long cacheMisses,
                                long promptTokens,
                                long completionTokens,
                                long cacheReadTokens,
                                long cacheWriteTokens) {

        public double hitRatio() {
            return requests == 0 ? 0.0 : (double) cacheHits / requests;
        }

        public double effectiveSavingsRatio() {
            long paid = promptTokens - cacheReadTokens;
            if (promptTokens == 0) return 0.0;
            return 1.0 - ((double) paid / promptTokens);
        }
    }
}
