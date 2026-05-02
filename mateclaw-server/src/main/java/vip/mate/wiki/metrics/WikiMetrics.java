package vip.mate.wiki.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized wiki metrics gateway.
 *
 * <p>All wiki-domain metrics flow through this Bean so we can:
 * <ul>
 *   <li>Standardize tag names ({@code kb_id}, {@code stage}, {@code provider})</li>
 *   <li>Gracefully no-op when MeterRegistry is absent (test env)</li>
 *   <li>Add cross-cutting concerns (cardinality limits, sampling) in one place</li>
 * </ul>
 *
 * <p>Mirrors the decorator pattern used in {@code vip.mate.memory.spi.decorator.MetricsMemoryProvider}
 * but is service-flavored rather than decorator-flavored: callers inject this bean and
 * push metrics through explicit methods rather than wrapping a delegate.
 *
 * <p>Method families:
 * <ul>
 *   <li>{@code recordCompile*} — wiki compile pipeline timings &amp; cache outcomes</li>
 *   <li>{@code recordRelation*} — page-to-page relation computation &amp; cache</li>
 *   <li>{@code recordRetrieval} — hybrid retrieval timings &amp; result counts</li>
 *   <li>{@code recordVision*} — image OCR / vision-in calls &amp; cache</li>
 *   <li>{@link #startTimer} — generic try-with-resources timer for ad-hoc spans</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WikiMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public WikiMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.info("[WikiMetrics] MeterRegistry not available; running in no-op mode");
        } else {
            log.info("[WikiMetrics] MeterRegistry available; metrics enabled");
        }
    }

    // ==================== Compile pipeline ====================

    /** Records the duration of one compile-pipeline stage. */
    public void recordCompileStage(String stage, Long kbId, Duration duration) {
        if (registry == null) {
            return;
        }
        getTimer("wiki.compile.stage",
                Tags.of(Tag.of("stage", stage),
                        Tag.of("kb_id", String.valueOf(kbId))))
                .record(duration);
    }

    /**
     * Records LLM prompt-cache outcome for a compile call.
     *
     * @param hit         whether the call hit a cached prefix
     * @param tokensSaved when {@code hit==true}, the count of cached input tokens
     */
    public void recordCompileCache(boolean hit, long tokensSaved) {
        if (registry == null) {
            return;
        }
        getCounter("wiki.compile.cache.outcome",
                Tags.of(Tag.of("outcome", hit ? "hit" : "miss"))).increment();
        if (hit && tokensSaved > 0) {
            getCounter("wiki.compile.cache.tokens_saved", Tags.empty())
                    .increment(tokensSaved);
        }
    }

    // ==================== Relation / Graph ====================

    public void recordRelationCompute(Long kbId, int nodeCount, Duration duration) {
        if (registry == null) {
            return;
        }
        getTimer("wiki.relation.compute",
                Tags.of(Tag.of("kb_id", String.valueOf(kbId))))
                .record(duration);
        getCounter("wiki.relation.compute.invocations", Tags.empty()).increment();
        // Distribution summary for nodeCount intentionally omitted to keep cardinality bounded;
        // surface via a separate gauge if needed.
        if (nodeCount > 0) {
            getCounter("wiki.relation.compute.nodes", Tags.empty()).increment(nodeCount);
        }
    }

    public void recordRelationCacheHit(boolean hit) {
        if (registry == null) {
            return;
        }
        getCounter("wiki.relation.cache",
                Tags.of(Tag.of("outcome", hit ? "hit" : "miss"))).increment();
    }

    // ==================== Retrieval ====================

    public void recordRetrieval(String mode, Duration duration, int resultCount) {
        if (registry == null) {
            return;
        }
        getTimer("wiki.retrieval.duration",
                Tags.of(Tag.of("mode", mode))).record(duration);
        if (resultCount > 0) {
            getCounter("wiki.retrieval.results",
                    Tags.of(Tag.of("mode", mode))).increment(resultCount);
        }
    }

    // ==================== OCR / Vision ====================

    public void recordVisionCall(String provider, boolean success, Duration duration) {
        if (registry == null) {
            return;
        }
        getTimer("wiki.vision.call",
                Tags.of(Tag.of("provider", provider),
                        Tag.of("outcome", success ? "success" : "failure")))
                .record(duration);
    }

    public void recordVisionCacheHit(boolean hit) {
        if (registry == null) {
            return;
        }
        getCounter("wiki.vision.cache",
                Tags.of(Tag.of("outcome", hit ? "hit" : "miss"))).increment();
    }

    // ==================== Generic try-with-resources timer ====================

    /**
     * Convenience for ad-hoc timers using the AutoCloseable pattern:
     *
     * <pre>{@code
     *   try (var sample = metrics.startTimer("wiki.compile.stage", "stage", "summary")) {
     *       // work...
     *   }
     * }</pre>
     */
    public WikiTimerSample startTimer(String name, String... keyValues) {
        return new WikiTimerSample(this, name, keyValues, System.nanoTime());
    }

    /** Used by {@link WikiTimerSample#close()} — package-private. */
    void recordTimer(String name, Tags tags, long durationNanos) {
        if (registry == null) {
            return;
        }
        getTimer(name, tags).record(Duration.ofNanos(durationNanos));
    }

    // ==================== Internal cache to avoid registering same meter twice ====================

    private Counter getCounter(String name, Tags tags) {
        return counterCache.computeIfAbsent(meterKey(name, tags),
                k -> registry.counter(name, tags));
    }

    private Timer getTimer(String name, Tags tags) {
        return timerCache.computeIfAbsent(meterKey(name, tags),
                k -> registry.timer(name, tags));
    }

    private static String meterKey(String name, Tags tags) {
        StringBuilder sb = new StringBuilder(name);
        for (Tag t : tags) {
            sb.append('|').append(t.getKey()).append('=').append(t.getValue());
        }
        return sb.toString();
    }
}
