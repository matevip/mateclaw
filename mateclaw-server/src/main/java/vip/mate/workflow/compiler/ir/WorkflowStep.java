package vip.mate.workflow.compiler.ir;

/**
 * Single step in a workflow's linear step array. {@code mode} holds the
 * type-specific configuration; common fields like timeout / retry policy /
 * outputVar live here so they apply to every mode without duplication.
 */
public record WorkflowStep(
        String name,
        String agentName,
        Long agentId,
        String promptTemplate,
        StepMode mode,
        Integer timeoutSecs,
        ErrorMode errorMode,
        String outputVar,
        String outputContentType
) {

    /** Resolved content type, defaulting to {@code text} when unspecified. */
    public String effectiveOutputContentType() {
        return outputContentType == null || outputContentType.isBlank()
                ? "text"
                : outputContentType;
    }
}
