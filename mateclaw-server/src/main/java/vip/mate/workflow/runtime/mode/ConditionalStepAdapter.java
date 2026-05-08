package vip.mate.workflow.runtime.mode;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.PebbleSubsetEvaluator;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.runtime.AgentStepExecutor;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

/**
 * {@code conditional} — runs the embedded agent step only when the configured
 * Pebble expression evaluates true against the current run context. A false
 * verdict yields {@link StepResult.State#SKIPPED}; an evaluation error fails
 * the step. Skipped steps still emit a run-step row so the history captures
 * the routing decision.
 */
@Component
public class ConditionalStepAdapter implements StepAdapter {

    private final PebbleSubsetEvaluator pebble;
    private final AgentStepExecutor executor;

    public ConditionalStepAdapter(PebbleSubsetEvaluator pebble, AgentStepExecutor executor) {
        this.pebble = pebble;
        this.executor = executor;
    }

    @Override
    public String typeName() { return "conditional"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.Conditional cond)) {
            return StepResult.failed("conditional adapter received non-conditional mode: "
                    + step.mode().typeName());
        }

        boolean truth;
        try {
            var compiled = pebble.parseExpression(cond.expression());
            truth = pebble.evaluateAsBoolean(compiled, context.templateContext());
        } catch (Exception e) {
            return StepResult.failed("conditional expression evaluation failed for step '"
                    + step.name() + "': " + e.getMessage());
        }

        if (!truth) {
            return StepResult.skipped("guard expression evaluated false");
        }

        return executor.run(step, context);
    }
}
