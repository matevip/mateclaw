package vip.mate.approval;

/**
 * Two-valued decision used when reconciling persisted approval state.
 * <p>
 * The frontend's {@code Message.status} union (mateclaw-ui/src/types/index.ts) only supports
 * {@code generating | completed | stopped | failed | awaiting_approval | interrupted}, so
 * approval decisions never appear at the message-status layer. {@code PendingApprovalMeta.status}
 * holds the decision ({@code approved} / {@code denied}); when the message itself was
 * persisted as {@code awaiting_approval} we collapse it back to one of the existing terminal
 * message states ({@code completed} for approved, {@code stopped} for denied) so downstream
 * consumers (history sanitizer, list ordering, stuck counters) keep working unchanged.
 * <p>
 * Timeout / superseded both map to {@link #DENIED} at the metadata layer (DB layer keeps
 * the more specific {@code TIMEOUT} / {@code SUPERSEDED} status for audit purposes).
 */
public enum MetadataDecision {

    APPROVED("approved", "completed"),
    DENIED("denied", "stopped");

    /** Target value for {@code metadata.pendingApproval.status}. */
    public final String pendingApprovalStatus;

    /** Target value for {@code MessageEntity.status} when source was {@code awaiting_approval}. */
    public final String messageStatus;

    MetadataDecision(String pendingApprovalStatus, String messageStatus) {
        this.pendingApprovalStatus = pendingApprovalStatus;
        this.messageStatus = messageStatus;
    }
}
