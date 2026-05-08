package vip.mate.trigger.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.agent.event.AgentLifecycleEvent;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;

import java.util.HashMap;
import java.util.Map;

/**
 * Forwards {@link AgentLifecycleEvent} into the trigger pipeline as
 * {@code agent_lifecycle} envelopes. Lives in the trigger module so the
 * agent runtime stays free of trigger / ingest dependencies, matching
 * the workflow_completion + channel_message bridge pattern.
 *
 * <p>The dedup key composes phase + agentId + timestamp so the same
 * agent flipping enabled / disabled repeatedly stays observable, but
 * an at-least-once retry of the same exact lifecycle event collapses.
 * Failures inside ingest are logged and swallowed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLifecycleEventBridge {

    private final TriggerEventIngestService ingestService;

    @EventListener
    public void onLifecycle(AgentLifecycleEvent event) {
        if (event == null) return;
        try {
            Map<String, Object> data = new HashMap<>();
            // The matcher reads `agentId` and `phase` out of the envelope
            // data; the field names mirror the matcher's vocabulary so
            // pattern_json can narrow precisely.
            data.put("agentId", event.agentId());
            if (event.agentName() != null) data.put("agentName", event.agentName());
            data.put("phase", event.phase());
            data.put("timestamp", event.timestamp());
            ingestService.ingest(new TriggerEventEnvelope(
                    event.workspaceId(),
                    "agent_lifecycle",
                    event.phase() + ":" + event.agentId() + ":" + event.timestamp(),
                    "system",
                    data));
        } catch (Exception e) {
            log.warn("[AgentLifecycleBridge] forwarding agent {} phase={} failed: {}",
                    event.agentId(), event.phase(), e.getMessage());
        }
    }
}
