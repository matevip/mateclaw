package vip.mate.agent.graph.edge;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ReasoningDispatcher 扩展测试：LLM 调用计数、fatal error 路由、停止语义
 */
class ReasoningDispatcherLlmCallCountTest {

    private ReasoningDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ReasoningDispatcher();
    }

    // ===== LLM 调用计数 =====

    @Nested
    @DisplayName("LLM 调用计数限制")
    class LlmCallCountLimit {

        // ReasoningDispatcher.LLM_CALL_MULTIPLIER = 5（默认 maxIterations=10 → 阈值 50）。
        // 本系列用例固定 MAX_ITERATIONS=10，所以 limit=50、limit-1=49。
        // 如果后续 MULTIPLIER 再改，这里的常量也要同步。

        @Test
        @DisplayName("达到上限但有最终回答 → 放行到 finalAnswerNode")
        void shouldAllowFinalAnswerEvenWhenLlmCallLimitReached() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 5, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 50, NEEDS_TOOL_CALL, false
            ));
            assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("达到上限且需要工具调用 → limitExceededNode")
        void shouldBlockToolCallWhenLlmCallLimitReached() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 5, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 50, NEEDS_TOOL_CALL, true
            ));
            assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("达到上限且需要总结 → limitExceededNode")
        void shouldBlockSummarizeWhenLlmCallLimitReached() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 5, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 50,
                    NEEDS_TOOL_CALL, false, SHOULD_SUMMARIZE, true
            ));
            assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("未达上限且需要工具调用 → actionNode")
        void shouldAllowToolCallWhenUnderLimit() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 5, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 15, NEEDS_TOOL_CALL, true
            ));
            assertEquals(ACTION_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("计数未设置时默认 0 → 不触发")
        void shouldUseDefaultWhenMissing() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 0, MAX_ITERATIONS, 10,
                    NEEDS_TOOL_CALL, true
            ));
            assertEquals(ACTION_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("迭代超限优先于 LLM 调用计数")
        void iterationLimitTakesPrecedence() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 10, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 5, NEEDS_TOOL_CALL, true
            ));
            assertEquals(LIMIT_EXCEEDED_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("计数恰好 limit-1 时不触发（边界值）")
        void shouldNotTriggerAtLimitMinusOne() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 5, MAX_ITERATIONS, 10,
                    LLM_CALL_COUNT, 49, NEEDS_TOOL_CALL, true
            ));
            assertEquals(ACTION_NODE, dispatcher.apply(state));
        }
    }

    // ===== Fatal error 路由 =====

    @Nested
    @DisplayName("Fatal error 路由语义")
    class FatalErrorRouting {

        @Test
        @DisplayName("fatal error 带 finalAnswer 时走 finalAnswerNode（不走 limitExceededNode）")
        void fatalErrorWithFinalAnswerGoesToFinalAnswerNode() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 2, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "[错误] 认证失败",
                    NEEDS_TOOL_CALL, false,
                    SHOULD_SUMMARIZE, false,
                    FINISH_REASON, "error_fallback"
            ));
            assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state),
                    "Fatal error with finalAnswer should go to finalAnswerNode, not limitExceededNode");
        }

        @Test
        @DisplayName("fatal error 叠加旧 SHOULD_SUMMARIZE=true 时仍走 finalAnswerNode（stale-state 防护）")
        void fatalErrorWithStaleShouldSummarize() throws Exception {
            // 模拟：前一轮 ObservationNode 设了 shouldSummarize=true，
            // 但本轮 ReasoningNode fatal error 显式清零了 shouldSummarize=false。
            // 验证不会因为残留标志被送去 summarizingNode / limitExceededNode。
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 2, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "[错误] 模型不可用",
                    NEEDS_TOOL_CALL, false,
                    SHOULD_SUMMARIZE, false,  // ReasoningNode 显式清零
                    FINISH_REASON, "error_fallback"
            ));
            assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("如果 SHOULD_SUMMARIZE 未被清零（stale=true），fatal error 会误路由（此为防御性对照测试）")
        void demonstrateStaleShouldSummarizeWouldMisroute() throws Exception {
            // 对照：如果 shouldSummarize 残留 true，即使有 finalAnswer 也会被送去 summarizingNode
            // 这证明 ReasoningNode 必须显式清零
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 2, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "[错误] 模型不可用",
                    NEEDS_TOOL_CALL, false,
                    SHOULD_SUMMARIZE, true  // 残留！
            ));
            // 会走到 llmCallCount check → summarizingNode，不是 finalAnswerNode
            assertNotEquals(FINAL_ANSWER_NODE, dispatcher.apply(state),
                    "Stale shouldSummarize=true would misroute — proves ReasoningNode must clear it");
        }
    }

    // ===== 用户停止语义 =====

    @Nested
    @DisplayName("用户停止路由语义")
    class StoppedRouting {

        @Test
        @DisplayName("用户停止无内容时走 finalAnswerNode（STOPPED 语义不被吞）")
        void stoppedWithNoContentGoesToFinalAnswerNode() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 2, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "",
                    NEEDS_TOOL_CALL, false,
                    SHOULD_SUMMARIZE, false,
                    FINISH_REASON, "stopped"
            ));
            assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("cancel 叠加旧 NEEDS_TOOL_CALL=true 时仍走 finalAnswerNode（stale-state 防护）")
        void cancelWithStaleNeedsToolCall() throws Exception {
            // 模拟：前一轮 ReasoningNode 设了 needsToolCall=true（请求工具调用），
            // 但本轮 cancel 显式清零了 needsToolCall=false。
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 3, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "",
                    NEEDS_TOOL_CALL, false,  // ReasoningNode 显式清零
                    SHOULD_SUMMARIZE, false, // ReasoningNode 显式清零
                    FINISH_REASON, "stopped"
            ));
            assertEquals(FINAL_ANSWER_NODE, dispatcher.apply(state));
        }

        @Test
        @DisplayName("如果 NEEDS_TOOL_CALL 未被清零（stale=true），cancel 会误路由（防御性对照测试）")
        void demonstrateStaleNeedsToolCallWouldMisroute() throws Exception {
            OverAllState state = new OverAllState(Map.of(
                    CURRENT_ITERATION, 3, MAX_ITERATIONS, 10,
                    FINAL_ANSWER, "",
                    NEEDS_TOOL_CALL, true,   // 残留！
                    SHOULD_SUMMARIZE, false,
                    FINISH_REASON, "stopped"
            ));
            // 会走到 actionNode，不是 finalAnswerNode
            assertEquals(ACTION_NODE, dispatcher.apply(state),
                    "Stale needsToolCall=true would misroute to actionNode");
        }
    }
}
