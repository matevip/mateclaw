package vip.mate.workspace.conversation.event;

/**
 * Fired AFTER {@link vip.mate.workspace.conversation.ConversationService#deleteConversation}
 * commits its DB cascade.
 * <p>
 * Subscribers must use this to clean up any in-memory or scheduled state keyed
 * on the deleted conversation — e.g. the approval pending map, async-task
 * pollers, SSE buffers, anything that survives independently of the DB row.
 * <p>
 * Published from a {@code TransactionSynchronization.afterCommit} hook so that
 * a listener observing this event can safely assume the conversation row,
 * its messages, and every cascaded associate row are gone. If the transaction
 * rolls back, the event is never published.
 */
public record ConversationDeletedEvent(String conversationId) {
}
