package vip.mate.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.approval.event.WorkflowApprovalResolvedEvent;
import vip.mate.workflow.compiler.PublishContext;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompiler;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.model.WorkflowRunPauseEntity;
import vip.mate.workflow.repository.WorkflowRevisionMapper;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.repository.WorkflowRunPauseMapper;

/**
 * Bridges {@link WorkflowApprovalResolvedEvent} from the approval module
 * into {@link WorkflowResumer}. Without this listener an operator who
 * clicks "approve" in the approval inbox would only flip the
 * {@code mate_tool_approval} row terminal — the workflow run stays
 * paused forever until someone separately POSTs the pause token to the
 * resume endpoint.
 *
 * <p>The listener:
 * <ol>
 *   <li>Looks up the pause row by {@code external_approval_id} matching
 *       the resolved approval row's id. If no pause row references this
 *       approval (operator path already resumed, or the approval wasn't
 *       linked to a workflow), we silently no-op.</li>
 *   <li>Re-loads the workflow revision's graph and recompiles it under
 *       the run's workspace ACL — same code path the resume controller
 *       uses, so an ACL change after publish doesn't sneak past.</li>
 *   <li>Maps the approval decision to a {@link WorkflowResumer.ResumeOutcome}:
 *       {@code approved}/{@code consumed} → {@code APPROVED};
 *       {@code denied}/{@code superseded} → {@code REJECTED};
 *       {@code timeout} → {@code TIMEOUT}.</li>
 *   <li>Calls {@code WorkflowResumer.resume} with the pause token. The
 *       resumer's idempotency check handles the race where the operator
 *       resumed the run via the REST endpoint a fraction of a second
 *       before the approval row resolved — second resume returns
 *       ALREADY_RESOLVED and the listener swallows it.</li>
 * </ol>
 *
 * <p>Lives in the workflow runtime module so the approval module stays
 * free of workflow / runner dependencies, mirroring the workflow ↔
 * trigger event-bridge pattern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalResumeBridge {

    private final WorkflowRunPauseMapper pauseMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRevisionMapper revisionMapper;
    private final WorkflowCompiler compiler;
    private final WorkflowAclPort aclPort;
    private final WorkflowResumer resumer;

    @EventListener
    public void onApprovalResolved(WorkflowApprovalResolvedEvent event) {
        if (event == null || event.approvalRowId() <= 0) return;
        WorkflowRunPauseEntity pause = pauseMapper.selectOne(
                new LambdaQueryWrapper<WorkflowRunPauseEntity>()
                        .eq(WorkflowRunPauseEntity::getExternalApprovalId, event.approvalRowId())
                        .isNull(WorkflowRunPauseEntity::getResumedAt)
                        .last("LIMIT 1"));
        if (pause == null) {
            // Either there's no workflow pause linked to this approval
            // (chat-driven approval), or the operator path already resumed
            // it. Both are fine.
            log.debug("[ApprovalResumeBridge] no open pause for approval row {} (pendingId={})",
                    event.approvalRowId(), event.pendingId());
            return;
        }

        WorkflowResumer.ResumeOutcome outcome = mapDecision(event.decision());
        if (outcome == null) {
            log.info("[ApprovalResumeBridge] decision '{}' on approval row {} is not a workflow-resume " +
                            "trigger; pause {} stays open",
                    event.decision(), event.approvalRowId(), pause.getId());
            return;
        }

        // Re-load the revision graph through the same compiler the resume
        // controller uses, so ACL changes after publish don't sneak past.
        WorkflowRunEntity run = runMapper.selectById(pause.getRunId());
        if (run == null) {
            log.warn("[ApprovalResumeBridge] pause {} references missing run {}",
                    pause.getId(), pause.getRunId());
            return;
        }
        WorkflowRevisionEntity revision = revisionMapper.selectById(run.getRevisionId());
        if (revision == null) {
            log.warn("[ApprovalResumeBridge] run {} references missing revision {}",
                    run.getId(), run.getRevisionId());
            return;
        }
        // PublishContext is (workspaceId, publisherId) — mind the order.
        WorkflowCompiler.Result compiled = compiler.compile(revision.getGraphJson(),
                new PublishContext(run.getWorkspaceId(), 0L), aclPort);
        if (!compiled.ok()) {
            log.warn("[ApprovalResumeBridge] revision {} failed to recompile on approval-driven resume",
                    revision.getId());
            return;
        }

        try {
            WorkflowResumer.Outcome result = resumer.resume(
                    compiled.graph(), pause.getPauseToken(), outcome, /* resumePayloadBody */ null);
            log.info("[ApprovalResumeBridge] resumed run {} via approval row {}: kind={}",
                    run.getId(), event.approvalRowId(), result.kind());
        } catch (Exception e) {
            // Idempotency is the resumer's job — this catch only triggers
            // on actual runtime failures during resume. Don't rethrow:
            // the approval row already moved off PENDING and we don't
            // want a transient resume failure to look like an
            // approval-side bug to upstream observers.
            log.warn("[ApprovalResumeBridge] resume failed for run {}: {}",
                    run.getId(), e.getMessage());
        }
    }

    private static WorkflowResumer.ResumeOutcome mapDecision(String decision) {
        if (decision == null) return null;
        return switch (decision.toLowerCase()) {
            case "approved", "consumed" -> WorkflowResumer.ResumeOutcome.APPROVED;
            case "denied", "superseded" -> WorkflowResumer.ResumeOutcome.REJECTED;
            case "timeout" -> WorkflowResumer.ResumeOutcome.TIMEOUT;
            // pending / running / unknown — no terminal outcome to map to.
            default -> null;
        };
    }
}
