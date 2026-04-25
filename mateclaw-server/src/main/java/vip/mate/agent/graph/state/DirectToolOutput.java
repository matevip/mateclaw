package vip.mate.agent.graph.state;

/**
 * RFC-052: full-text result of a tool call that declared {@code returnDirect=true}.
 *
 * <p>The result is delivered to the user (and persisted to {@code mate_message})
 * verbatim, but is intentionally <em>not</em> placed into any subsequent LLM
 * prompt — see {@link MateClawStateKeys#DIRECT_TOOL_OUTPUTS} and
 * {@code FinalAnswerNode}'s direct branch.
 *
 * @param toolCallId   the tool call id from the originating LLM response
 * @param toolName     the resolved tool name
 * @param fullResult   the complete tool result, never truncated or spilled
 * @param executedAtMs epoch milliseconds when the tool returned
 *
 * @author MateClaw Team
 */
public record DirectToolOutput(
        String toolCallId,
        String toolName,
        String fullResult,
        long executedAtMs
) {
}
