package vip.mate.wiki.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * AutoCloseable timer sample for try-with-resources usage.
 *
 * <p>Created by {@link WikiMetrics#startTimer(String, String...)}; closing the
 * sample records the elapsed nanoseconds against the named timer with the
 * given key-value tags. Closing twice is a no-op — safe inside try/finally
 * blocks that may also have an explicit {@code close()} call.
 *
 * @author MateClaw Team
 */
public class WikiTimerSample implements AutoCloseable {

    private final WikiMetrics metrics;
    private final String name;
    private final Tags tags;
    private final long startNanos;
    private boolean closed;

    WikiTimerSample(WikiMetrics metrics, String name, String[] keyValues, long startNanos) {
        this.metrics = metrics;
        this.name = name;
        Tags built = Tags.empty();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            built = built.and(Tag.of(keyValues[i], keyValues[i + 1]));
        }
        this.tags = built;
        this.startNanos = startNanos;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        metrics.recordTimer(name, tags, System.nanoTime() - startNanos);
    }
}
