package vip.mate.agent.event;

/**
 * Spring application event fired when an agent's lifecycle state changes.
 * The trigger module subscribes via {@code @EventListener} and forwards
 * the payload through {@code TriggerEventIngestService} so triggers of
 * pattern type {@code agent_lifecycle} can fan out to workflows.
 *
 * <p>{@code phase} matches the matcher's vocabulary: {@code spawned} for
 * a fresh create, {@code enabled} / {@code disabled} for a flag flip,
 * {@code terminated} for a delete. {@code crashed} is reserved for v1
 * once the agent runtime grows a structured error hook.
 *
 * <p>The dedup key downstream is {@code phase + ":" + agentId + ":" +
 * timestamp}; that's stable across retries of the same operation but
 * lets the same agent flip enabled/disabled repeatedly without the
 * trigger pipeline collapsing the events.
 */
public record AgentLifecycleEvent(
        long workspaceId,
        long agentId,
        String agentName,
        String phase,
        long timestamp
) {}
