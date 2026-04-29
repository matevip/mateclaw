package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import vip.mate.agent.graph.plan.state.PlanStateKeys;

import java.util.Map;

/**
 * 直接回答节点
 * <p>
 * When PlanGenerationNode classifies the user's message as a simple
 * question, this node propagates {@code direct_answer} into
 * {@code FINAL_SUMMARY} so the graph terminates with the answer in the
 * canonical place every downstream consumer reads from.
 * <p>
 * Earlier versions skipped writing FINAL_SUMMARY when
 * {@code CONTENT_STREAMED=true}, on the theory that broadcastContent had
 * already pushed the text and a second copy in FINAL_SUMMARY would cause
 * double persistence. That was wrong: broadcastContent goes directly to
 * the SSE side-channel via {@code streamTracker.broadcastDelta} and does
 * NOT participate in the DB segment that ChatController accumulates from
 * the structured stream. Skipping FINAL_SUMMARY left
 * {@code AgentService.chat()} (the sync entry used by every IM channel)
 * with an empty reply, which silently dropped DingTalk / Slack /
 * Telegram replies on the direct-answer path. It also left
 * {@code mate_message.content} empty on the web channel — the SSE
 * client saw the answer in real time but reopening the conversation
 * showed a blank assistant turn.
 * <p>
 * Re-broadcast suppression is the responsibility of the stream layer,
 * not this node:
 * {@link vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent#chatStructuredStream}
 * tags the FINAL_SUMMARY delta as {@code persistOnly} when
 * CONTENT_STREAMED is true, and {@code ChatController} respects that flag
 * to persist without re-pushing.
 *
 * @author MateClaw Team
 */
public class DirectAnswerNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String directAnswer = state.value(PlanStateKeys.DIRECT_ANSWER, "");
        return Map.of(PlanStateKeys.FINAL_SUMMARY, directAnswer);
    }
}
