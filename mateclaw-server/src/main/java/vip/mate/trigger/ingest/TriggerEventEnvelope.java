package vip.mate.trigger.ingest;

import java.util.Map;

/**
 * Generic event envelope used by upstream sources (channel webhooks,
 * agent-lifecycle hooks, workflow-completion hooks, ad-hoc REST callers)
 * to feed the trigger pipeline. The pipeline owns dedup / rate-limit /
 * bot-self filtering; sources only need to fill this record:
 *
 * <ul>
 *   <li>{@code workspaceId} — scopes which triggers can fire on this event.</li>
 *   <li>{@code patternType} — matched against {@code mate_trigger.pattern_type};
 *       the ingest looks up only triggers whose pattern type equals this.</li>
 *   <li>{@code eventId} — stable upstream identifier used as the dedup key
 *       when present; the ingest falls back to a content hash when blank.</li>
 *   <li>{@code senderId} — the upstream actor; used by the bot-self filter
 *       to drop events that originate from MateClaw's own outbound traffic.</li>
 *   <li>{@code data} — free-form payload exposed to the trigger's payload
 *       template under {@code event.*}.</li>
 * </ul>
 */
public record TriggerEventEnvelope(
        long workspaceId,
        String patternType,
        String eventId,
        String senderId,
        Map<String, Object> data
) {
    public TriggerEventEnvelope {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
