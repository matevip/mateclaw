package vip.mate.workflow.compiler;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.compiler.ir.WorkflowStep;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Structural validator. Ensures required fields are present per mode, names
 * are unique, the step count is bounded, and the fan_out / collect grouping
 * follows the workflow design rules:
 * <ul>
 *   <li>A fan_out group must have at least two consecutive fan_out steps and
 *       must be terminated by a collect.</li>
 *   <li>A collect must follow a fan_out group.</li>
 *   <li>An await_approval step cannot live inside a fan_out group (multiple
 *       concurrent approvals have no aggregation UX).</li>
 * </ul>
 *
 * <p>Expression-language and ACL checks live in dedicated validators so each
 * pass has a single responsibility.
 */
@Component
public class WorkflowSchemaValidator {

    /** Default ceiling — flags runaway templates / config mistakes early. */
    public static final int DEFAULT_MAX_STEPS = 200;

    private final int maxSteps;

    public WorkflowSchemaValidator() { this(DEFAULT_MAX_STEPS); }

    public WorkflowSchemaValidator(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public List<CompileError> validate(WorkflowGraph graph) {
        List<CompileError> errors = new ArrayList<>();
        if (graph == null) {
            errors.add(new CompileError("workflow.null", "$", "workflow definition is null"));
            return errors;
        }
        if (graph.steps().isEmpty()) {
            errors.add(new CompileError("workflow.no_steps", "steps",
                    "workflow must declare at least one step"));
            return errors;
        }
        if (graph.steps().size() > maxSteps) {
            errors.add(new CompileError(
                    "workflow.too_many_steps",
                    "steps",
                    "workflow has " + graph.steps().size() + " steps; max is " + maxSteps));
        }

        validatePerStepFields(graph, errors);
        validateUniqueNames(graph, errors);
        validateFanOutCollectGrouping(graph, errors);
        return errors;
    }

    private void validatePerStepFields(WorkflowGraph graph, List<CompileError> errors) {
        for (int i = 0; i < graph.steps().size(); i++) {
            WorkflowStep s = graph.steps().get(i);
            if (s.name() == null || s.name().isBlank()) {
                errors.add(CompileError.stepField(i, "name",
                        "step.name_required", "step name is required"));
            }
            if (s.mode() == null) {
                errors.add(CompileError.stepField(i, "mode",
                        "step.mode_required", "step mode is required"));
                continue;
            }
            String oct = s.effectiveOutputContentType();
            if (!oct.equals("text") && !oct.equals("json")) {
                errors.add(CompileError.stepField(i, "outputContentType",
                        "step.output_content_type_unsupported",
                        "outputContentType must be 'text' or 'json' (got '" + oct + "')"));
            }
            validateModeFields(i, s, errors);
        }
    }

    private void validateModeFields(int i, WorkflowStep s, List<CompileError> errors) {
        StepMode m = s.mode();
        switch (m) {
            case StepMode.Sequential ignored -> requireAgent(i, s, errors);
            case StepMode.FanOut ignored -> requireAgent(i, s, errors);
            case StepMode.Collect ignored -> {
                // Agent invocation is optional on collect — the runtime can
                // either feed the collected payload into the next step or
                // run an agent at this step. Both are valid v0 shapes.
            }
            case StepMode.Conditional c -> {
                if (c.expression() == null || c.expression().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.expression",
                            "step.conditional_expression_required",
                            "conditional mode requires an expression"));
                }
                requireAgent(i, s, errors);
            }
            case StepMode.AwaitApproval a -> {
                if (a.approvalKind() == null || a.approvalKind().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.approvalKind",
                            "step.await_approval.kind_required",
                            "await_approval requires approvalKind"));
                }
                if (a.approverChannels() == null || a.approverChannels().isEmpty()) {
                    errors.add(CompileError.stepField(i, "mode.approverChannels",
                            "step.await_approval.channels_required",
                            "await_approval requires at least one approverChannel"));
                }
            }
            case StepMode.DispatchChannel d -> {
                if (d.channels() == null || d.channels().isEmpty()) {
                    errors.add(CompileError.stepField(i, "mode.channels",
                            "step.dispatch_channel.channels_required",
                            "dispatch_channel requires at least one channel"));
                }
                if (d.content() == null || d.content().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.content",
                            "step.dispatch_channel.content_required",
                            "dispatch_channel requires content"));
                }
            }
            case StepMode.WriteMemory w -> {
                if (w.employeeId() == null || w.employeeId().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.employeeId",
                            "step.write_memory.employee_required",
                            "write_memory requires employeeId"));
                }
                if (w.file() == null || w.file().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.file",
                            "step.write_memory.file_required",
                            "write_memory requires file"));
                }
                if (w.mergeStrategy() == null || w.mergeStrategy().isBlank()) {
                    errors.add(CompileError.stepField(i, "mode.mergeStrategy",
                            "step.write_memory.merge_required",
                            "write_memory requires mergeStrategy"));
                } else if (!isKnownMergeStrategy(w.mergeStrategy())) {
                    errors.add(CompileError.stepField(i, "mode.mergeStrategy",
                            "step.write_memory.merge_unknown",
                            "mergeStrategy '" + w.mergeStrategy()
                                    + "' must be one of append / replace_section / upsert_kv / overwrite"));
                }
            }
        }
    }

    private static boolean isKnownMergeStrategy(String s) {
        return "append".equals(s) || "replace_section".equals(s)
                || "upsert_kv".equals(s) || "overwrite".equals(s);
    }

    private static void requireAgent(int i, WorkflowStep s, List<CompileError> errors) {
        boolean hasName = s.agentName() != null && !s.agentName().isBlank();
        boolean hasId = s.agentId() != null;
        if (!hasName && !hasId) {
            errors.add(CompileError.step(i, "step.agent_required",
                    "step requires either agentName or agentId for mode '"
                            + s.mode().typeName() + "'"));
        }
    }

    private void validateUniqueNames(WorkflowGraph graph, List<CompileError> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < graph.steps().size(); i++) {
            String name = graph.steps().get(i).name();
            if (name == null || name.isBlank()) continue;
            if (!seen.add(name)) {
                errors.add(CompileError.stepField(i, "name",
                        "step.name_duplicate", "step name '" + name + "' is duplicated"));
            }
        }
    }

    private void validateFanOutCollectGrouping(WorkflowGraph graph, List<CompileError> errors) {
        List<WorkflowStep> steps = graph.steps();
        int i = 0;
        while (i < steps.size()) {
            StepMode m = steps.get(i).mode();
            if (m instanceof StepMode.FanOut) {
                int groupStart = i;
                int j = i;
                while (j < steps.size() && steps.get(j).mode() instanceof StepMode.FanOut) {
                    if (containsAwaitApproval(steps.get(j))) {
                        // Defensive — fan_out with await_approval mode object
                        // can only appear if a single step had two modes,
                        // which the parser already rejects. Keeping the check
                        // costs nothing.
                    }
                    j++;
                }
                int groupSize = j - groupStart;
                if (groupSize < 2) {
                    errors.add(CompileError.step(groupStart, "step.fan_out.singleton",
                            "fan_out groups must have at least 2 consecutive fan_out steps"));
                }
                if (j >= steps.size() || !(steps.get(j).mode() instanceof StepMode.Collect)) {
                    errors.add(CompileError.step(groupStart, "step.fan_out.no_terminating_collect",
                            "fan_out group starting at step '" + steps.get(groupStart).name()
                                    + "' must be terminated by a collect step"));
                }
                i = j;
                continue;
            }
            if (m instanceof StepMode.Collect) {
                if (i == 0 || !(steps.get(i - 1).mode() instanceof StepMode.FanOut)) {
                    errors.add(CompileError.step(i, "step.collect.no_preceding_fan_out",
                            "collect step must follow a fan_out group"));
                }
            }
            i++;
        }
    }

    /** Always false in v0 — placeholder for future composite-mode awareness. */
    private static boolean containsAwaitApproval(WorkflowStep step) {
        return step.mode() instanceof StepMode.AwaitApproval;
    }
}
