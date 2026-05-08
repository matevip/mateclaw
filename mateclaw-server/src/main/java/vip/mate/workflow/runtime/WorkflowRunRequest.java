package vip.mate.workflow.runtime;

import java.util.Map;

/**
 * Inputs the runner needs to start a single workflow run. Identity fields
 * ({@code workflowId}, {@code revisionId}, {@code workspaceId}) tie the run
 * row back to the published revision the runner walks. {@code triggeredBy}
 * is a free-form label written into {@code mate_workflow_run.triggered_by}
 * — the runner doesn't interpret it.
 */
public record WorkflowRunRequest(
        long workflowId,
        long revisionId,
        long workspaceId,
        String triggeredBy,
        Map<String, Object> inputs
) {
    public WorkflowRunRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }
}
