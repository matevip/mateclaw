package vip.mate.workflow.runtime;

/**
 * Outcome reported by a {@link StepAdapter#execute}. Records:
 * <ul>
 *   <li>{@link State} — succeeded / skipped / failed / paused; the runner
 *       translates the first three to {@code mate_workflow_run_step.state}
 *       and the last to a graceful run-pause exit.</li>
 *   <li>{@code outputPayloadUri} — payload URI for the step's output, or
 *       {@code null} when the step produced nothing (skipped, collect, paused).</li>
 *   <li>{@code outputContentType} — resolved content type, defaults to
 *       {@code text}; lets the runner persist {@code output_content_type}
 *       without rebuilding the step contract.</li>
 *   <li>{@code outputValue} — the in-memory value to publish into the
 *       run context's {@code outputs} map. {@link String} for text content,
 *       {@link java.util.Map} / {@link java.util.List} for json content.
 *       {@code null} when the step has no {@code outputVar}.</li>
 *   <li>{@code outputSummary} / {@code errorMessage} — short labels for the
 *       step row; both optional.</li>
 *   <li>{@code pauseToken} — set when {@code state == PAUSED}; the resume
 *       entry key the resumer expects callers to present.</li>
 * </ul>
 */
public record StepResult(
        State state,
        String outputPayloadUri,
        String outputContentType,
        Object outputValue,
        String outputSummary,
        String errorMessage,
        String pauseToken
) {

    public enum State { SUCCEEDED, SKIPPED, FAILED, PAUSED }

    public static StepResult succeeded(String payloadUri, String contentType, Object value, String summary) {
        return new StepResult(State.SUCCEEDED, payloadUri, contentType, value, summary, null, null);
    }

    public static StepResult skipped(String reason) {
        return new StepResult(State.SKIPPED, null, null, null, reason, null, null);
    }

    public static StepResult failed(String errorMessage) {
        return new StepResult(State.FAILED, null, null, null, null, errorMessage, null);
    }

    public static StepResult paused(String pauseToken, String summary) {
        return new StepResult(State.PAUSED, null, null, null, summary, null, pauseToken);
    }
}
