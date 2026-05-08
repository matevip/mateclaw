package vip.mate.workflow.runtime.mode;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.runtime.AgentStepExecutor;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

/**
 * {@code sequential} — runs after the previous step finishes and threads its
 * output forward via {@code outputs[outputVar]}. The default mode for any
 * agent-call step that does not need parallel or guarded execution.
 */
@Component
public class SequentialStepAdapter implements StepAdapter {

    private final AgentStepExecutor executor;

    public SequentialStepAdapter(AgentStepExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String typeName() { return "sequential"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        return executor.run(step, context);
    }
}
