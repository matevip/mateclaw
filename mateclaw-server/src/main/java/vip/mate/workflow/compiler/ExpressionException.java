package vip.mate.workflow.compiler;

/**
 * Raised by {@link PebbleSubsetEvaluator} on parse or evaluate failures so
 * callers (the schema validator, output-content-type checker, and runtime)
 * see a single exception type for all expression-language errors.
 */
public class ExpressionException extends RuntimeException {
    public ExpressionException(String message) { super(message); }
    public ExpressionException(String message, Throwable cause) { super(message, cause); }
}
