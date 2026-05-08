package vip.mate.workflow.compiler;

import org.springframework.stereotype.Component;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowGraph;
import vip.mate.workflow.compiler.ir.WorkflowStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Publish-time access-control validator. For each step that touches an
 * external scope (agent / channel / employee memory), the validator asks
 * the {@link WorkflowAclPort} whether the reference resolves inside the
 * publishing workspace. Any negative answer is recorded as a
 * {@link CompileError}; downstream the publish flow refuses to write a new
 * revision when the error list is non-empty.
 *
 * <p>Pure structural ACL — workflow-level actor identity (the publisher
 * versus the runtime acting agent) is enforced separately when steps are
 * registered with the runtime, where {@code AgentBindingService.getEffectiveToolNames}
 * applies the per-agent tool ACL.
 */
@Component
public class WorkflowAclValidator {

    public List<CompileError> validate(WorkflowGraph graph, PublishContext ctx, WorkflowAclPort port) {
        if (graph == null || graph.steps().isEmpty()) {
            return List.of();
        }
        List<CompileError> errors = new ArrayList<>();
        for (int i = 0; i < graph.steps().size(); i++) {
            WorkflowStep s = graph.steps().get(i);
            checkAgent(i, s, ctx, port, errors);
            checkChannels(i, s, ctx, port, errors);
            checkEmployee(i, s, ctx, port, errors);
        }
        return errors;
    }

    private static void checkAgent(int i, WorkflowStep s, PublishContext ctx,
                                   WorkflowAclPort port, List<CompileError> errors) {
        if (s.mode() instanceof StepMode.AwaitApproval
                || s.mode() instanceof StepMode.Collect
                || s.mode() instanceof StepMode.DispatchChannel
                || s.mode() instanceof StepMode.WriteMemory) {
            return; // these modes do not invoke an agent at runtime
        }
        if (s.agentId() != null) {
            if (!port.agentIdExists(ctx.workspaceId(), s.agentId())) {
                errors.add(CompileError.stepField(i, "agentId",
                        "acl.agent_not_resolvable",
                        "agentId " + s.agentId() + " does not resolve to an enabled agent in this workspace"));
            }
            return;
        }
        if (s.agentName() != null && !s.agentName().isBlank()
                && !port.agentExists(ctx.workspaceId(), s.agentName())) {
            errors.add(CompileError.stepField(i, "agentName",
                    "acl.agent_not_resolvable",
                    "agent '" + s.agentName() + "' does not resolve to an enabled agent in this workspace"));
        }
    }

    private static void checkChannels(int i, WorkflowStep s, PublishContext ctx,
                                      WorkflowAclPort port, List<CompileError> errors) {
        if (!(s.mode() instanceof StepMode.DispatchChannel d)) {
            return;
        }
        if (d.channels() == null) return;
        for (int c = 0; c < d.channels().size(); c++) {
            String ch = d.channels().get(c);
            if (ch == null || ch.isBlank()) continue;
            if (!port.channelAllowed(ctx.workspaceId(), ch)) {
                errors.add(CompileError.stepField(i, "mode.channels[" + c + "]",
                        "acl.channel_not_allowed",
                        "channel '" + ch + "' is not on the workspace allowlist"));
            }
        }
    }

    private static void checkEmployee(int i, WorkflowStep s, PublishContext ctx,
                                      WorkflowAclPort port, List<CompileError> errors) {
        if (!(s.mode() instanceof StepMode.WriteMemory w)) {
            return;
        }
        // Pebble templates resolve at runtime — do not ACL-check expressions
        // that aren't a literal employee id. Literal forms are the safe
        // common case worth guarding.
        if (w.employeeId() == null || w.employeeId().isBlank()) {
            return;
        }
        if (containsTemplate(w.employeeId())) {
            return;
        }
        if (!port.employeeInWorkspace(ctx.workspaceId(), w.employeeId())) {
            errors.add(CompileError.stepField(i, "mode.employeeId",
                    "acl.employee_not_in_workspace",
                    "employeeId '" + w.employeeId() + "' is not a member of this workspace"));
        }
    }

    private static boolean containsTemplate(String s) {
        return s != null && s.contains("{{");
    }
}
