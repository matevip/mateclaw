package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;

import java.util.List;
import java.util.Map;

/**
 * 最终回答节点
 * <p>
 * 汇聚所有终止路径的最终回答生成：
 * <ul>
 *   <li>直接回答路径：使用 ReasoningNode 产出的 finalAnswer</li>
 *   <li>Summarizing 路径：基于 summarizedContext 构建回答</li>
 *   <li>LimitExceeded 路径：使用 finalAnswerDraft</li>
 * </ul>
 * <p>
 * 负责设置最终的 finalAnswer、finalThinking 和 finishReason。
 * 保留上游节点设置的 CONTENT_STREAMED / THINKING_STREAMED 标志位。
 *
 * @author MateClaw Team
 */
@Slf4j
public class FinalAnswerNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        String finalAnswer;
        String finalThinking;
        FinishReason finishReason;

        // RFC-052 — RETURN_DIRECT path takes the highest priority after stopping checks.
        // The full text of the direct tool result(s) becomes the final answer
        // verbatim; no LLM call has been made on it. Thinking from the LLM
        // call that *decided* to invoke the direct tool is preserved (it has
        // already been streamed; this just keeps the state symmetric with the
        // NORMAL / SUMMARIZED / LIMIT_EXCEEDED branches below).
        if (accessor.returnDirectTriggered()) {
            List<DirectToolOutput> outputs = accessor.directToolOutputs();
            if (!outputs.isEmpty()) {
                String assembled = assembleDirectAnswer(outputs);
                String currentThinking = accessor.currentThinking();
                String existingThinking = accessor.finalThinking();
                String preservedThinking = !currentThinking.isEmpty() ? currentThinking : existingThinking;
                log.info("[FinalAnswerNode] RETURN_DIRECT — assembled final answer from {} direct " +
                        "tool output(s), {} chars (thinking preserved: {} chars)",
                        outputs.size(), assembled.length(), preservedThinking.length());
                var builder = MateClawStateAccessor.output()
                        .finalAnswer(assembled)
                        .finishReason(FinishReason.RETURN_DIRECT);
                if (!preservedThinking.isEmpty()) {
                    builder.finalThinking(preservedThinking);
                }
                return builder.build();
            }
            log.warn("[FinalAnswerNode] RETURN_DIRECT_TRIGGERED=true but DIRECT_TOOL_OUTPUTS empty; " +
                    "falling through to default final-answer assembly");
        }

        // 审批等待路径：Graph 因 AWAITING_APPROVAL 终止，保留已流式推送的内容用于持久化
        if (accessor.awaitingApproval()) {
            String preservedContent = accessor.streamedContent();
            String preservedThinking = !accessor.streamedThinking().isEmpty()
                    ? accessor.streamedThinking() : accessor.currentThinking();
            log.info("[FinalAnswerNode] AWAITING_APPROVAL — preserving streamed content " +
                            "({} chars, thinking {} chars) for persistence",
                    preservedContent.length(), preservedThinking.length());
            var builder = MateClawStateAccessor.output()
                    .finalAnswer(preservedContent)
                    .finishReason(FinishReason.NORMAL)
                    .contentStreamed(true)
                    .thinkingStreamed(true);
            if (!preservedThinking.isEmpty()) {
                builder.finalThinking(preservedThinking);
            }
            return builder.build();
        }

        // 优先级：finalAnswerDraft（来自 limitExceeded/summarizing 路径）> finalAnswer（来自 reasoning 直接路径）
        String draft = accessor.finalAnswerDraft();
        String existingAnswer = accessor.finalAnswer();
        String existingReason = accessor.finishReason();
        String existingThinking = accessor.finalThinking();
        String currentThinking = accessor.currentThinking();

        if (!draft.isEmpty()) {
            // 来自 limitExceeded 或 summarizing + LLM 回答
            finalAnswer = draft;
            // currentThinking 来自 SummarizingNode 或 LimitExceededNode
            finalThinking = !currentThinking.isEmpty() ? currentThinking : existingThinking;
            finishReason = parseFinishReason(existingReason);
            log.info("[FinalAnswerNode] Using finalAnswerDraft ({} chars), reason={}",
                    finalAnswer.length(), finishReason);

        } else if (!existingAnswer.isEmpty()) {
            // 来自 reasoning 直接回答（或 stopped partial）
            finalAnswer = existingAnswer;
            finalThinking = !currentThinking.isEmpty() ? currentThinking : existingThinking;
            // 尊重上游已设的 finishReason（如 STOPPED），只有未设时才默认 NORMAL
            finishReason = !existingReason.isEmpty() ? parseFinishReason(existingReason) : FinishReason.NORMAL;
            log.info("[FinalAnswerNode] Using existing finalAnswer ({} chars), reason={}",
                    finalAnswer.length(), finishReason);

        } else {
            // 无 draft 也无 finalAnswer
            // 先检查是否用户主动停止（无内容的 STOPPED 是合法终止，不是错误）
            if (parseFinishReason(existingReason) == FinishReason.STOPPED) {
                finalAnswer = "";
                finalThinking = "";
                finishReason = FinishReason.STOPPED;
                log.info("[FinalAnswerNode] User stopped before any content was generated, preserving STOPPED");
            } else {
                // 异常兜底：使用 summarizedContext
                String summary = accessor.summarizedContext();
                if (!summary.isEmpty()) {
                    finalAnswer = summary;
                    finalThinking = currentThinking;
                    finishReason = FinishReason.SUMMARIZED;
                    log.warn("[FinalAnswerNode] No finalAnswer or draft found, falling back to summarizedContext");
                } else {
                    finalAnswer = "Failed to generate a response, please retry.";
                    finalThinking = "";
                    finishReason = FinishReason.ERROR_FALLBACK;
                    log.error("[FinalAnswerNode] No answer source available, returning fallback");
                }
            }
        }

        // 不重置 CONTENT_STREAMED/THINKING_STREAMED，保留上游节点的标志
        var builder = MateClawStateAccessor.output()
                .finalAnswer(finalAnswer)
                .finishReason(finishReason);

        if (!finalThinking.isEmpty()) {
            builder.finalThinking(finalThinking);
        }

        return builder.build();
    }

    /**
     * RFC-052 §2.5: assemble the final answer from direct tool outputs.
     * Single output ⇒ verbatim full text. Multiple outputs ⇒ each prefixed
     * with a Markdown heading so the user can tell them apart.
     */
    private static String assembleDirectAnswer(List<DirectToolOutput> outputs) {
        if (outputs.size() == 1) {
            return outputs.get(0).fullResult();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            DirectToolOutput out = outputs.get(i);
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append("### ").append(out.toolName()).append("\n");
            sb.append(out.fullResult());
        }
        return sb.toString();
    }

    private FinishReason parseFinishReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return FinishReason.NORMAL;
        }
        for (FinishReason fr : FinishReason.values()) {
            if (fr.getValue().equals(reason)) {
                return fr;
            }
        }
        return FinishReason.NORMAL;
    }
}
