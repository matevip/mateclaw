package vip.mate.workflow.compiler.ir;

import java.util.List;
import java.util.Map;

/**
 * Tagged record describing the control-flow mode of a single workflow step.
 * v0 supports four base modes (sequential / fan_out / collect / conditional)
 * and three MateClaw-specific modes (await_approval / dispatch_channel /
 * write_memory). loop and invoke_skill are deferred to v1.
 */
public sealed interface StepMode {

    String typeName();

    /** Sequential — runs after the previous step, threads its output forward. */
    record Sequential() implements StepMode {
        @Override public String typeName() { return "sequential"; }
    }

    /** Fan-out — schedules in parallel with adjacent fan_out steps. */
    record FanOut() implements StepMode {
        @Override public String typeName() { return "fan_out"; }
    }

    /** Collect — joins the most recent fan_out group. */
    record Collect() implements StepMode {
        @Override public String typeName() { return "collect"; }
    }

    /** Conditional — runs only when the Pebble expression evaluates true. */
    record Conditional(String expression) implements StepMode {
        @Override public String typeName() { return "conditional"; }
    }

    /** Await approval — pauses the run until the approval row resolves. */
    record AwaitApproval(
            String approvalKind,
            List<String> approverChannels,
            String approvalMessage,
            Integer timeoutSecs
    ) implements StepMode {
        @Override public String typeName() { return "await_approval"; }
    }

    /** Dispatch channel — fan out a payload to one or more configured channels. */
    record DispatchChannel(
            List<String> channels,
            Map<String, String> targets,
            String content
    ) implements StepMode {
        @Override public String typeName() { return "dispatch_channel"; }
    }

    /** Write memory — apply a merge strategy to an employee's memory file. */
    record WriteMemory(
            String employeeId,
            String file,
            String mergeStrategy,
            String content
    ) implements StepMode {
        @Override public String typeName() { return "write_memory"; }
    }
}
