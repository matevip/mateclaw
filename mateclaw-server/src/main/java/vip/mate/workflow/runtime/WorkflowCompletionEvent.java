package vip.mate.workflow.runtime;

/**
 * Spring application event fired when a workflow run reaches a terminal
 * state ({@code succeeded} / {@code failed}). The trigger module
 * subscribes via {@code @EventListener} and pushes the payload through
 * {@link vip.mate.trigger.ingest.TriggerEventIngestService} so downstream
 * triggers (e.g. {@code workflow_completion} pattern) can chain off the
 * outcome.
 *
 * <p>Going through the event bus instead of injecting the trigger
 * service directly into the workflow runner breaks the
 * Runner ↔ Dispatcher ↔ Ingest ↔ Runner circular dependency that Spring
 * would otherwise refuse to construct.
 */
public record WorkflowCompletionEvent(
        long runId,
        long workflowId,
        long revisionId,
        long workspaceId,
        String state,
        String finalOutputRef,
        String errorMessage
) {}
