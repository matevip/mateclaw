package vip.mate.workflow.compiler;

/**
 * Single workflow compile-time diagnostic. {@code path} points at the offending
 * field using a JSONPath-ish notation rooted at the workflow definition (e.g.
 * {@code steps[2].mode.expression} or {@code steps[5]}).
 */
public record CompileError(String code, String path, String message) {

    /** Convenience for step-rooted errors. */
    public static CompileError step(int index, String code, String message) {
        return new CompileError(code, "steps[" + index + "]", message);
    }

    /** Step-rooted error pointing at a specific sub-field. */
    public static CompileError stepField(int index, String field, String code, String message) {
        return new CompileError(code, "steps[" + index + "]." + field, message);
    }
}
