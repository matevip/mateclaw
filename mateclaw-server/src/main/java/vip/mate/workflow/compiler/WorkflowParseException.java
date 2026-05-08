package vip.mate.workflow.compiler;

/**
 * Thrown by {@link WorkflowParser} when the JSON wire format cannot be turned
 * into a {@link vip.mate.workflow.compiler.ir.WorkflowGraph}. Distinct from
 * {@link CompileError} so that wire-format problems never reach the validator
 * passes — those operate exclusively on a syntactically valid IR.
 */
public class WorkflowParseException extends RuntimeException {
    public WorkflowParseException(String message) { super(message); }
    public WorkflowParseException(String message, Throwable cause) { super(message, cause); }
}
