package vip.mate.workflow.compiler;

import java.util.List;

/**
 * Thrown by {@link WorkflowCompiler.Result#requireOk()} when at least one
 * compile error was raised. The error list is preserved on the exception so
 * callers (REST endpoints, persistence layers) can surface every problem
 * back to the publishing user without losing diagnostic context.
 */
public class WorkflowCompileFailedException extends RuntimeException {

    private final List<CompileError> errors;

    public WorkflowCompileFailedException(List<CompileError> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    public List<CompileError> errors() {
        return errors;
    }

    private static String buildMessage(List<CompileError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "workflow compile failed";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("workflow compile failed with ").append(errors.size()).append(" error(s):");
        for (CompileError e : errors) {
            sb.append("\n  - [").append(e.code()).append("] ").append(e.path())
                    .append(": ").append(e.message());
        }
        return sb.toString();
    }
}
