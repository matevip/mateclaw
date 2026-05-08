package vip.mate.trigger.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.trigger.ingest.TriggerEventEnvelope;
import vip.mate.trigger.ingest.TriggerEventIngestService;
import vip.mate.workflow.runtime.WorkflowCompletionEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges {@link WorkflowCompletionEvent} from the workflow module into the
 * trigger ingest pipeline. Lives in the trigger module so the workflow
 * runtime stays free of trigger / ingest dependencies — that's how we
 * dodge the Runner ↔ Dispatcher ↔ Ingest ↔ Runner cycle Spring would
 * otherwise refuse to construct.
 *
 * <p>Each terminal-state run is translated into a {@code workflow_completion}
 * envelope with a deterministic {@code wf-run-{runId}} eventId, so the
 * {@code mate_trigger_event} unique constraint dedups any re-publish
 * (e.g. a runner crash + retry). Failures inside the ingest pipeline are
 * logged and swallowed — a bad downstream trigger MUST NOT corrupt the
 * just-completed run.
 *
 * <p>The listener fires in the runner thread by default; if a downstream
 * ingest does heavy work, switch to {@code @Async} once a dedicated
 * executor is wired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCompletionEventBridge {

    private final TriggerEventIngestService ingestService;

    @EventListener
    public void onCompletion(WorkflowCompletionEvent event) {
        if (event == null) return;
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("sourceWorkflowId", event.workflowId());
            data.put("revisionId", event.revisionId());
            data.put("runId", event.runId());
            data.put("state", event.state());
            if (event.finalOutputRef() != null) data.put("finalOutputRef", event.finalOutputRef());
            if (event.errorMessage() != null) data.put("errorMessage", event.errorMessage());
            TriggerEventEnvelope envelope = new TriggerEventEnvelope(
                    event.workspaceId(),
                    "workflow_completion",
                    "wf-run-" + event.runId(),
                    "system",
                    data);
            ingestService.ingest(envelope);
        } catch (Exception e) {
            log.warn("[WorkflowCompletionBridge] forwarding run {} completion failed: {}",
                    event.runId(), e.getMessage());
        }
    }

}
