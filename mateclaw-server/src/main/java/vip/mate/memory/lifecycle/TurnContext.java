package vip.mate.memory.lifecycle;

/**
 * Minimal turn-scoped context; built once per turn at AgentService level.
 *
 * @param agentId        the agent ID
 * @param conversationId the conversation ID
 * @param sessionId      session ID (may equal conversationId in Phase 1)
 * @param turnNumber     turn sequence number within the conversation
 * @param userQuery      the current user message
 * @author MateClaw Team
 */
public record TurnContext(
        Long agentId,
        String conversationId,
        String sessionId,
        int turnNumber,
        String userQuery
) {}
