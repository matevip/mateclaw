package vip.mate.workflow.runtime.mode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.workflow.compiler.ir.StepMode;
import vip.mate.workflow.compiler.ir.WorkflowStep;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.model.WorkflowRunStepEntity;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;
import vip.mate.workflow.repository.WorkflowRunStepMapper;
import vip.mate.workflow.runtime.StepAdapter;
import vip.mate.workflow.runtime.StepResult;
import vip.mate.workflow.runtime.WorkflowRunContext;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@code await_approval} — pauses the run pending an external approval
 * decision. Inserts a {@code mate_workflow_run_pause} row keyed by a fresh
 * {@code pauseToken}, then returns {@link StepResult.State#PAUSED} so the
 * runner can short-circuit and mark the run row {@code paused}.
 *
 * <p><b>Resolution path (v0):</b>
 * <ol>
 *   <li>Operator UI lists paused runs via {@code GET /api/v1/workflows/runs/paused},
 *       which returns the run + the active pause record (including the
 *       {@code pauseToken}).</li>
 *   <li>Operator picks an outcome and POSTs to
 *       {@code /api/v1/workflows/runs/{runId}/resume} with the
 *       {@code pauseToken} and {@code outcome ∈ {approved, rejected, timeout, cancelled}}.</li>
 *   <li>{@code WorkflowResumer} marks the pause row resolved and advances
 *       the run state machine.</li>
 * </ol>
 *
 * <p>The pause row's {@code resume_deadline} is honoured when the step
 * declares a {@code timeoutSecs}; otherwise it stays {@code null} and the
 * resumer treats the pause as open-ended.
 *
 * <p>The {@code external_approval_id} column on the pause row links to the
 * {@code mate_tool_approval} row created via
 * {@link ApprovalWorkflowService#requestWorkflowApproval} so the workflow
 * pause is visible in the same approval inbox the tool-approval flow uses.
 * Resolution still goes through {@code WorkflowResumeController} +
 * pauseToken — the approval row is for operator visibility today; v1 wires
 * the resolve→resume callback so an inbox decision can also fire the
 * resumer.
 */
@Component
public class AwaitApprovalStepAdapter implements StepAdapter {

    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunStepMapper stepMapper;
    /** Optional — not all test contexts wire the approval module up. The
     *  adapter falls back to a no-op approval row when null. */
    @Autowired(required = false)
    private ApprovalWorkflowService approvalService;

    public AwaitApprovalStepAdapter(WorkflowRunPauseMapper pauseMapper,
                                    WorkflowRunStepMapper stepMapper) {
        this.pauseMapper = pauseMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    public String typeName() { return "await_approval"; }

    @Override
    public StepResult execute(WorkflowStep step, WorkflowRunContext context) {
        if (!(step.mode() instanceof StepMode.AwaitApproval cfg)) {
            return StepResult.failed("await_approval adapter received non-await mode: "
                    + step.mode().typeName());
        }

        // Look up the freshly opened step row so we can link the pause to it.
        WorkflowRunStepEntity stepRow = stepMapper.selectOne(new LambdaQueryWrapper<WorkflowRunStepEntity>()
                .eq(WorkflowRunStepEntity::getRunId, context.runId())
                .eq(WorkflowRunStepEntity::getStepName, step.name())
                .orderByDesc(WorkflowRunStepEntity::getId)
                .last("LIMIT 1"));
        if (stepRow == null) {
            return StepResult.failed("await_approval could not locate its run-step row");
        }

        String pauseToken = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // Insert the pause row first so we have a stable id to reference
        // even if the approval-service call below fails.
        WorkflowRunPauseEntity pause = new WorkflowRunPauseEntity();
        pause.setRunId(context.runId());
        pause.setStepId(stepRow.getId());
        pause.setPauseKind("await_approval");
        pause.setPauseToken(pauseToken);
        pause.setPausedAt(now);
        if (cfg.timeoutSecs() != null && cfg.timeoutSecs() > 0) {
            pause.setResumeDeadline(now.plusSeconds(cfg.timeoutSecs()));
        }
        pauseMapper.insert(pause);

        // Bridge into the approval inbox: create a mate_tool_approval row so
        // the workflow pause shows up alongside tool approvals, then write
        // the row id back as external_approval_id for the future
        // resolve→resume callback. Failures here are non-fatal — the run
        // is still resolvable via pauseToken + WorkflowResumeController.
        if (approvalService != null) {
            try {
                Long approvalId = approvalService.requestWorkflowApproval(
                        context.workspaceId(),
                        context.runId(),
                        stepRow.getId(),
                        cfg.approvalKind(),
                        cfg.approvalMessage(),
                        cfg.approverChannels(),
                        cfg.timeoutSecs());
                if (approvalId != null) {
                    pause.setExternalApprovalId(approvalId);
                    pauseMapper.updateById(pause);
                }
            } catch (Exception e) {
                // Non-fatal — log and continue. The pause row is the
                // canonical record for v0; the approval row is a parallel
                // visibility surface that can rebuild later if needed.
                org.slf4j.LoggerFactory.getLogger(AwaitApprovalStepAdapter.class)
                        .warn("await_approval failed to create approval row for run {}: {}",
                                context.runId(), e.getMessage());
            }
        }

        return StepResult.paused(pauseToken,
                "awaiting " + (cfg.approvalKind() == null ? "approval" : cfg.approvalKind()));
    }
}
