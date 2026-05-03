package vip.mate.agent.delegation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration knobs for {@link SubagentHeartbeat}.
 *
 * <p>Defaults are tuned so a wedged child surfaces visibly to the parent UI
 * without firing on legitimately slow tool runs:
 * <ul>
 *   <li>Idle (no tool running) → 5 cycles × 30 s = 150 s before stale.</li>
 *   <li>In a tool → 20 cycles × 30 s = 600 s before stale.</li>
 * </ul>
 *
 * <p>The in-tool threshold MUST stay greater than or equal to
 * {@code child_hard_timeout / intervalSec}. If it fires before the per-child
 * hard cap then the cap stops being the source of truth for "this child is
 * dead" and operators see ambiguous telemetry.
 */
@Component
@ConfigurationProperties("mateclaw.delegation.heartbeat")
public class SubagentHeartbeatConfig {

    /**
     * Heartbeat check interval in seconds. Lower values make the parent
     * transcript more responsive at the cost of scheduler overhead.
     */
    private int intervalSec = 30;

    /**
     * Stale threshold (in heartbeat cycles) when the child has no current
     * tool in flight. With the default 30 s interval this is 150 s, tight
     * enough that a wedged child does not mask a legitimate gateway timeout.
     */
    private int staleCyclesIdle = 5;

    /**
     * Stale threshold (in heartbeat cycles) while the child is inside a
     * tool. Generous enough to tolerate slow tools (large file reads, slow
     * LLM calls). Must be at least the per-child hard timeout divided by
     * {@link #intervalSec}, otherwise stale fires before the hard cap and
     * obscures fallback semantics.
     */
    private int staleCyclesInTool = 20;

    public int getIntervalSec() {
        return intervalSec;
    }

    public void setIntervalSec(int intervalSec) {
        this.intervalSec = intervalSec;
    }

    public int getStaleCyclesIdle() {
        return staleCyclesIdle;
    }

    public void setStaleCyclesIdle(int staleCyclesIdle) {
        this.staleCyclesIdle = staleCyclesIdle;
    }

    public int getStaleCyclesInTool() {
        return staleCyclesInTool;
    }

    public void setStaleCyclesInTool(int staleCyclesInTool) {
        this.staleCyclesInTool = staleCyclesInTool;
    }
}
