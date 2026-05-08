package vip.mate.workflow.runtime.mode;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

/**
 * {@code collect} — barrier that closes the most recent fan_out group. The
 * runner awaits the parallel branches before invoking this adapter, then
 * publishes their merged outputs into the run context. The adapter itself
 * does no agent work; it simply records a step row so the run history shows
 * where the group joined and produces no payload of its own.
 */
@Component
public class CollectStepAdapter implements StepAdapter {

    @Override
    public String typeName() { return "collect"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        return StepResult.succeeded(null, null, null, "fan_out group joined");
    }
}
