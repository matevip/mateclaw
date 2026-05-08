package vip.mate.workflow.runtime;

/**
 * Public outcome of a workflow run. {@code state} mirrors the row state
 * machine ({@code succeeded} / {@code failed}); {@code finalOutputUri} is
 * the payload URI of the last non-skipped step's output, or {@code null}
 * when no step produced output. {@code errorMessage} is populated when the
 * run aborted; {@code null} on success.
 */
public record WorkflowRunResult(
        long runId,
        String state,
        String finalOutputUri,
        String errorMessage
) {
}
