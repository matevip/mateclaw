package vip.mate.approval;

/**
 * Result of an {@link ApprovalWorkflowService} state-change operation.
 * <p>
 * Returned by {@code resolve}, {@code resolveAndConsume}, {@code consumeApproved},
 * {@code cancelStalePending}, and (PR 3) {@code denyAllByConversation} so the caller can:
 * <ul>
 *   <li>broadcast a {@code tool_approval_resolved} SSE event AFTER the DB transaction
 *       commits — SSE is not a rollback-capable resource and must not live inside the
 *       persistence transaction (RFC-067 §4.2);</li>
 *   <li>distinguish "did anything happen" from "nothing to do, idempotent return"
 *       so noisy log spam / repeated UI events stay suppressed when two paths race
 *       to resolve the same approval;</li>
 *   <li>reach the consumed payload (tool call JSON) for replay without going back to
 *       the memory map a second time.</li>
 * </ul>
 *
 * @param pendingId          the approval that was operated on (always populated)
 * @param conversationId     conversation owning the approval (null on alreadyResolved
 *                           when only the id was given)
 * @param toolName           tool that was awaiting approval (null on alreadyResolved)
 * @param decision           one of: {@code approved}, {@code denied}, {@code consumed},
 *                           {@code superseded}, {@code timeout}, {@code already_resolved}
 * @param consumedSnapshot   the in-memory record at the moment of consume; non-null
 *                           only when {@code decision == "consumed"} (used by replay)
 * @param dbSynced           {@code true} iff the DB row's status flipped successfully
 *                           in this call (false on already_resolved or DB failure)
 * @param messagesRewritten  how many {@code mate_message} rows had their
 *                           {@code metadata.pendingApproval.status} reconciled
 */
public record ResolveOutcome(
        String pendingId,
        String conversationId,
        String toolName,
        String decision,
        PendingApproval consumedSnapshot,
        boolean dbSynced,
        int messagesRewritten
) {

    public static ResolveOutcome alreadyResolved(String pendingId) {
        return new ResolveOutcome(pendingId, null, null, "already_resolved", null, false, 0);
    }

    public static ResolveOutcome resolved(PendingApproval snapshot, String decision,
                                          boolean dbSynced, int messagesRewritten) {
        return new ResolveOutcome(
                snapshot.getPendingId(),
                snapshot.getConversationId(),
                snapshot.getToolName(),
                decision,
                null,
                dbSynced,
                messagesRewritten
        );
    }

    public static ResolveOutcome consumed(PendingApproval snapshot,
                                          boolean dbSynced, int messagesRewritten) {
        return new ResolveOutcome(
                snapshot.getPendingId(),
                snapshot.getConversationId(),
                snapshot.getToolName(),
                "consumed",
                snapshot,
                dbSynced,
                messagesRewritten
        );
    }

    public boolean isAlreadyResolved() {
        return "already_resolved".equals(decision);
    }

    public boolean isConsumed() {
        return "consumed".equals(decision);
    }
}
