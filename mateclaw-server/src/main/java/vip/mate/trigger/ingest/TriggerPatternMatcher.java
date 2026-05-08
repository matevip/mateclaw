package vip.mate.trigger.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.trigger.model.TriggerEntity;

import java.util.Map;

/**
 * Decides whether a trigger's stored {@code pattern_json} actually matches
 * an inbound envelope, beyond the coarse {@code (workspaceId, patternType)}
 * filter the SQL query already does.
 *
 * <p>Without this layer the ingest service broadcasts every event to every
 * trigger in the same workspace that happens to share a {@code patternType},
 * which is the event-storm hazard the design has warned about — one channel
 * message would fire every channel-message trigger regardless of intent.
 *
 * <p>v0 supports four pattern shapes:
 * <ul>
 *   <li><b>cron</b> — never matches an inbound envelope. Cron triggers run
 *       through the scheduler, not the ingest pipeline.</li>
 *   <li><b>channel_message</b> — optional {@code channelType} narrows by
 *       which adapter the envelope came from; optional {@code senderEquals}
 *       narrows to a specific sender id.</li>
 *   <li><b>agent_lifecycle</b> — optional {@code agentId} narrows to a
 *       specific agent's lifecycle events; optional {@code phase} narrows
 *       to {@code spawned} / {@code terminated} / {@code crashed}.</li>
 *   <li><b>content_match</b> — required {@code substring} must appear in
 *       the envelope's {@code data.content} field (case-insensitive); this
 *       is the explicit pattern that the design always intended to require
 *       payload-level evaluation.</li>
 *   <li><b>workflow_completion</b> — optional {@code sourceWorkflowId}
 *       narrows to a specific upstream workflow; optional {@code stateFilter}
 *       narrows to {@code completed} / {@code failed} / {@code any}.</li>
 *   <li><b>webhook</b> — opaque pass-through. v0 doesn't filter further.</li>
 * </ul>
 *
 * <p>Unknown pattern types fail closed (no match) so a typo'd or future
 * pattern type can't silently fire every workspace trigger.
 */
@Slf4j
@Component
public class TriggerPatternMatcher {

    private final ObjectMapper objectMapper;

    public TriggerPatternMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean matches(TriggerEntity trigger, TriggerEventEnvelope envelope) {
        String type = trigger.getPatternType();
        if (type == null) return false;
        JsonNode pattern = parsePattern(trigger);
        return switch (type) {
            case "cron" -> false;                           // scheduler-driven, not ingested
            case "channel_message" -> matchesChannelMessage(pattern, envelope);
            case "agent_lifecycle" -> matchesAgentLifecycle(pattern, envelope);
            case "content_match" -> matchesContent(pattern, envelope);
            case "workflow_completion" -> matchesWorkflowCompletion(pattern, envelope);
            case "webhook" -> true;                         // pass-through; secret check happens at the HTTP boundary
            default -> {
                log.warn("Trigger {} uses unknown patternType '{}' — failing closed",
                        trigger.getId(), type);
                yield false;
            }
        };
    }

    private JsonNode parsePattern(TriggerEntity trigger) {
        String json = trigger.getPatternJson();
        if (json == null || json.isBlank()) return objectMapper.nullNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            // A trigger with malformed pattern_json should never have been
            // accepted at create / update time; fail closed at fire time.
            log.warn("Trigger {} pattern_json parse failed: {}", trigger.getId(), e.getMessage());
            return objectMapper.nullNode();
        }
    }

    private boolean matchesChannelMessage(JsonNode pattern, TriggerEventEnvelope envelope) {
        if (envelope == null) return false;
        // channelType lives in envelope.data ("channelType" key) — the upstream
        // ChannelWebhookController stuffs it there. envelope itself is generic
        // and doesn't have a typed channel field.
        String wantChannel = textOrNull(pattern, "channelType");
        if (wantChannel != null) {
            Object actual = envelope.data() == null ? null : envelope.data().get("channelType");
            if (!(actual instanceof String s) || !wantChannel.equalsIgnoreCase(s)) return false;
        }
        String wantSender = textOrNull(pattern, "senderEquals");
        if (wantSender != null && !wantSender.equals(envelope.senderId())) {
            return false;
        }
        // contentContains is the keyword filter the templates relied on
        // — without it a "feishu + 发票" trigger would fire on every
        // feishu message. Matches case-insensitively against
        // envelope.data.content, the same field content_match uses.
        // Keeping the field on channel_message also folds the redundant
        // content_match pattern type into the more general channel one;
        // content_match remains supported for backwards compatibility
        // via {@link #matchesContent}.
        String wantContains = textOrNull(pattern, "contentContains");
        if (wantContains != null) {
            Object content = envelope.data() == null ? null : envelope.data().get("content");
            if (!(content instanceof String body)) return false;
            if (!body.toLowerCase().contains(wantContains.toLowerCase())) return false;
        }
        return true;
    }

    private boolean matchesAgentLifecycle(JsonNode pattern, TriggerEventEnvelope envelope) {
        Map<String, Object> data = envelope.data();
        if (data == null) return false;
        Long wantAgent = longOrNull(pattern, "agentId");
        if (wantAgent != null) {
            Object actual = data.get("agentId");
            if (!(actual instanceof Number n) || n.longValue() != wantAgent) return false;
        }
        String wantPhase = textOrNull(pattern, "phase");
        if (wantPhase != null) {
            Object phase = data.get("phase");
            if (!(phase instanceof String s) || !wantPhase.equalsIgnoreCase(s)) return false;
        }
        return true;
    }

    private boolean matchesContent(JsonNode pattern, TriggerEventEnvelope envelope) {
        String needle = textOrNull(pattern, "substring");
        if (needle == null || needle.isBlank()) {
            // content_match without a substring is a misconfiguration — refuse
            // to fire blanket-on-every-event rather than acting as a wildcard.
            return false;
        }
        Map<String, Object> data = envelope.data();
        if (data == null) return false;
        Object content = data.get("content");
        if (!(content instanceof String s)) return false;
        return s.toLowerCase().contains(needle.toLowerCase());
    }

    private boolean matchesWorkflowCompletion(JsonNode pattern, TriggerEventEnvelope envelope) {
        Map<String, Object> data = envelope.data();
        if (data == null) return false;
        Long wantSource = longOrNull(pattern, "sourceWorkflowId");
        if (wantSource != null) {
            Object actual = data.get("sourceWorkflowId");
            if (!(actual instanceof Number n) || n.longValue() != wantSource) return false;
        }
        String wantState = textOrNull(pattern, "stateFilter");
        if (wantState != null && !"any".equalsIgnoreCase(wantState)) {
            Object stateObj = data.get("state");
            if (!(stateObj instanceof String actualState)) return false;
            // The runtime emits "succeeded" / "failed"; pattern authors
            // commonly type "completed" to mean "non-failed terminal".
            // Treat the two as equivalent so authors don't have to care
            // which vocabulary the runner happens to use today.
            if ("completed".equalsIgnoreCase(wantState)) {
                if (!"succeeded".equalsIgnoreCase(actualState)) return false;
            } else if (!wantState.equalsIgnoreCase(actualState)) {
                return false;
            }
        }
        return true;
    }

    private static String textOrNull(JsonNode node, String key) {
        if (node == null || !node.hasNonNull(key)) return null;
        String s = node.get(key).asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Long longOrNull(JsonNode node, String key) {
        if (node == null || !node.hasNonNull(key)) return null;
        JsonNode v = node.get(key);
        if (v.isNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
