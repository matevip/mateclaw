package vip.mate.llm.failover;

/**
 * Outcome of a single provider health probe.
 *
 * @param success    true if the provider responded usable
 * @param latencyMs  wall-clock latency of the probe in milliseconds; {@code 0} if it threw before measuring
 * @param errorMessage human-readable failure detail; {@code null} on success
 */
public record ProbeResult(boolean success, long latencyMs, String errorMessage) {

    public static ProbeResult ok(long latencyMs) {
        return new ProbeResult(true, latencyMs, null);
    }

    public static ProbeResult fail(long latencyMs, String message) {
        return new ProbeResult(false, latencyMs, message);
    }
}
