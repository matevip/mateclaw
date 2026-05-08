package vip.mate.workflow.compiler.ir;

/**
 * Per-step error policy. {@code Retry} carries the retry budget; {@code Fail}
 * propagates the error to the run; {@code Skip} marks the step succeeded with
 * no output (downstream steps that referenced its outputVar see the previous
 * variable value, mirroring the conditional-false rule).
 */
public sealed interface ErrorMode {

    String typeName();

    record Fail() implements ErrorMode {
        @Override public String typeName() { return "fail"; }
    }

    record Skip() implements ErrorMode {
        @Override public String typeName() { return "skip"; }
    }

    record Retry(int maxRetries) implements ErrorMode {
        @Override public String typeName() { return "retry"; }
    }
}
