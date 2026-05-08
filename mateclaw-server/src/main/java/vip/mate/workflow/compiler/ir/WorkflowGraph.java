package vip.mate.workflow.compiler.ir;

import java.util.List;

/**
 * Immutable in-memory representation of a parsed workflow definition. The
 * compiler operates exclusively on this IR; the original JSON is the wire
 * format and is not retained past the parse stage.
 */
public record WorkflowGraph(
        String schemaVersion,
        List<WorkflowInput> inputs,
        List<WorkflowStep> steps
) {
    public WorkflowGraph {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
