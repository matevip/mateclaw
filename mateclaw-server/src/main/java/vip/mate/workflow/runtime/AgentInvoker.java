package vip.mate.workflow.runtime;

/**
 * SPI for "render prompt → run agent → return text response". Kept thin so
 * unit tests can stub agent execution without booting the full StateGraph
 * runtime. Production binding lives in {@link DefaultAgentInvoker} and
 * delegates to {@code AgentService.chat(...)}.
 */
public interface AgentInvoker {

    /**
     * Invoke the resolved agent with {@code prompt} and return the agent's
     * final response text. {@code conversationId} is the ephemeral conversation
     * id created per workflow step — the runner generates this so each step
     * has its own conversational scope.
     */
    String invoke(long agentId, String prompt, String conversationId);

    /**
     * Resolve a workspace-scoped agent name to its id. Returns {@code null}
     * when the agent does not exist or is disabled.
     */
    Long resolveAgentId(long workspaceId, String agentName);
}
