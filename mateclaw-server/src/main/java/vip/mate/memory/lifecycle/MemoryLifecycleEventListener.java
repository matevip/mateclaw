package vip.mate.memory.lifecycle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.event.ConversationCompletedEvent;

/**
 * Dispatches ConversationCompletedEvent to MemoryManager.onSessionEnd unconditionally.
 *
 * <p>Contract: every successfully-persisted conversation end must reach all memory
 * providers, regardless of whether summarization / nudge preconditions held.
 *
 * <p>This is a separate listener from PostConversationMemoryListener on purpose —
 * that one has four early returns tied to summarize/nudge heuristics. Those are fine
 * for summarize/nudge business logic, but none of them are appropriate gates for
 * provider-level session-end signals (rfc-037 §3.7, decision D10).
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryLifecycleEventListener {

    private final MemoryLifecycleMediator mediator;
    private final MemoryProperties props;

    @Async
    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent event) {
        if (!props.isLifecycleMediatorEnabled()) return;
        try {
            mediator.onSessionEnd(event.agentId(), event.conversationId());
        } catch (Exception e) {
            log.debug("[Memory] onSessionEnd dispatch failed (non-fatal): {}", e.getMessage());
        }
    }
}
