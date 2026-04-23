package vip.mate.memory.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import vip.mate.workspace.conversation.ConversationService;

/**
 * Single source of truth for publishing {@link ConversationCompletedEvent}.
 * <p>
 * Callers (chat controllers, channel routers, cron executor, talk-mode handler …)
 * should invoke {@link #publish(Long, String, String, String, String)} once per
 * fully completed turn — i.e. after both the user and assistant messages have
 * been persisted via {@link ConversationService}.
 * <p>
 * This helper hides the boilerplate of looking up message count, building the
 * event record, and swallowing publish-time exceptions so that a memory hook
 * failure never surfaces to the user.
 * <p>
 * Introduced to close the P0 gap in RFC-040 §6.1: three historical call sites
 * hand-rolled the same try/catch, and several real entry points forgot to
 * publish the event at all. Routing every site through this bean makes the
 * memory production pipeline observable and uniform.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationCompletionPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final ConversationService conversationService;

    /**
     * Publish a {@link ConversationCompletedEvent} for the given turn.
     * Must be called <em>after</em> the assistant message is persisted, because
     * {@link PostConversationMemoryListener} uses {@code messageCount} as a
     * summarization threshold and downstream summarizer reads the DB.
     *
     * @param agentId        agent id the turn ran under
     * @param conversationId conversation/session id
     * @param userMessage    last user message (for summarizer heuristics)
     * @param assistantReply agent's final reply (may be empty for replay-only turns)
     * @param source         trigger source label: {@code web}, {@code channel},
     *                       {@code cron}, {@code talk}, {@code replay}, …
     */
    public void publish(Long agentId,
                        String conversationId,
                        String userMessage,
                        String assistantReply,
                        String source) {
        if (agentId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            int messageCount = conversationService.getMessageCount(conversationId);
            eventPublisher.publishEvent(new ConversationCompletedEvent(
                    agentId,
                    conversationId,
                    userMessage != null ? userMessage : "",
                    assistantReply != null ? assistantReply : "",
                    messageCount,
                    source != null ? source : "unknown"));
        } catch (Exception e) {
            log.debug("[Memory] Failed to publish ConversationCompletedEvent (source={}, conv={}): {}",
                    source, conversationId, e.getMessage());
        }
    }
}
