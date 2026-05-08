package vip.mate.approval.event;

/**
 * Spring application event fired when a workflow-scoped approval row is
 * resolved (approved / denied / timed out / superseded). The workflow
 * module subscribes via {@code @EventListener}, looks up the pause row
 * by {@code mate_workflow_run_pause.external_approval_id == approvalRowId},
 * and idempotently calls {@code WorkflowResumer.resume} so the pause
 * actually advances.
 *
 * <p>Without this bridge, an operator who clicks "approve" in the
 * approval inbox would only flip the {@code mate_tool_approval} row to
 * APPROVED — the workflow run would stay paused forever until someone
 * separately POSTed the pause token to the resume endpoint. That's the
 * "approval is just a visibility surface, not an actual approval"
 * trap RFC §3.4 calls out.
 *
 * <p>{@code approvalRowId} is the {@code mate_tool_approval.id} long key,
 * NOT the {@code pendingId} string. Pause rows store the long id in
 * {@code external_approval_id}, so the listener can find the right
 * pause with a single equality query.
 *
 * <p>{@code decision} mirrors the resolve vocabulary so the listener
 * can route to the right {@code WorkflowResumer.ResumeOutcome}:
 * <ul>
 *   <li>{@code approved} / {@code consumed} → APPROVED</li>
 *   <li>{@code denied} / {@code superseded} → REJECTED</li>
 *   <li>{@code timeout} → TIMEOUT</li>
 * </ul>
 */
public record WorkflowApprovalResolvedEvent(
        long approvalRowId,
        String pendingId,
        String decision,
        Long workspaceId
) {}
