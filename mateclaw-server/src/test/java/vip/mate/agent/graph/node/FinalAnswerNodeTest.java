package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * FinalAnswerNode 单元测试
 */
class FinalAnswerNodeTest {

    private FinalAnswerNode node;

    @BeforeEach
    void setUp() {
        node = new FinalAnswerNode();
    }

    @Test
    @DisplayName("正常 finalAnswer 直接使用")
    void shouldUseExistingFinalAnswer() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "这是最终回答"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("这是最终回答", result.get(FINAL_ANSWER));
        assertEquals("normal", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("finalAnswerDraft 优先于 finalAnswer")
    void draftTakesPrecedence() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "旧回答",
                FINAL_ANSWER_DRAFT, "新草稿"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("新草稿", result.get(FINAL_ANSWER));
    }

    @Test
    @DisplayName("ERROR_FALLBACK finalAnswer 保留错误文案和 finishReason")
    void shouldPreserveErrorFallbackAnswer() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "[错误] 认证失败: Invalid API Key",
                FINISH_REASON, "error_fallback"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("[错误] 认证失败: Invalid API Key", result.get(FINAL_ANSWER));
        assertEquals("error_fallback", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("STOPPED 且无内容时保留 STOPPED 语义（不降级为 ERROR_FALLBACK）")
    void shouldPreserveStoppedWhenNoContent() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINAL_ANSWER, "",
                FINISH_REASON, "stopped"
        ));
        Map<String, Object> result = node.apply(state);
        // 关键断言：finishReason 保持 STOPPED，不被改成 ERROR_FALLBACK
        assertEquals("stopped", result.get(FINISH_REASON),
                "Empty finalAnswer with STOPPED should not become ERROR_FALLBACK");
        // finalAnswer 保持空（用户停止且无内容是合法的）
        assertEquals("", result.get(FINAL_ANSWER));
    }

    @Test
    @DisplayName("STOPPED 且无 finalAnswer 键时也保留 STOPPED 语义")
    void shouldPreserveStoppedWhenNoFinalAnswerKey() throws Exception {
        OverAllState state = new OverAllState(Map.of(
                FINISH_REASON, "stopped"
        ));
        Map<String, Object> result = node.apply(state);
        assertEquals("stopped", result.get(FINISH_REASON));
    }

    @Test
    @DisplayName("无任何内容且非 STOPPED 时降级为 ERROR_FALLBACK")
    void shouldFallbackWhenNoContentAndNotStopped() throws Exception {
        OverAllState state = new OverAllState(Map.of());
        Map<String, Object> result = node.apply(state);
        // Fallback 文案在 Jobs-voice 改写后统一为英文（commit 01c3888）。
        assertEquals("Failed to generate a response, please retry.", result.get(FINAL_ANSWER));
        assertEquals("error_fallback", result.get(FINISH_REASON));
    }
}
