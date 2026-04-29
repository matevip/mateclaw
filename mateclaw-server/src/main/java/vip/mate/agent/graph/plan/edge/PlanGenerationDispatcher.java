package vip.mate.agent.graph.plan.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import vip.mate.agent.graph.plan.state.PlanStateKeys;

/**
 * Routes the graph after the triage node.
 * <ul>
 *   <li>{@code needs_planning=false} → {@code DIRECT_ANSWER_NODE} (direct answer, no tools)</li>
 *   <li>{@code needs_planning=true} → {@code STEP_EXECUTION_NODE} (single- or multi-step plan)</li>
 * </ul>
 * <p>
 * If the triage key is absent, we default to {@code direct_answer} — an unset
 * {@code needs_planning} means triage did not run to completion, and Occam's
 * razor says treat it as "no planning" rather than auto-splitting a task the
 * system never classified. The previous default ({@code true}) biased every
 * unresolved request into a multi-step plan, which was the main source of the
 * "every request splits into subtasks" behavior (see RFC-008).
 */
public class PlanGenerationDispatcher implements EdgeAction {

    @Override
    public String apply(OverAllState state) {
        boolean needsPlanning = state.value(PlanStateKeys.NEEDS_PLANNING, false);
        if (!needsPlanning) {
            return PlanStateKeys.DIRECT_ANSWER_NODE;
        }
        return PlanStateKeys.STEP_EXECUTION_NODE;
    }
}
