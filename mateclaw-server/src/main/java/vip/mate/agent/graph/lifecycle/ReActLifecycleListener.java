package vip.mate.agent.graph.lifecycle;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ReAct 状态图生命周期监听器
 * <p>
 * 利用 spring-ai-alibaba-graph-core 的 {@link GraphLifecycleListener} 接口，
 * 在图执行的关键节点输出结构化日志，不与业务逻辑耦合。
 * <p>
 * 通过 {@code CompileConfig.builder().withLifecycleListener(new ReActLifecycleListener())} 注册。
 * <p>
 * 输出日志示例：
 * <pre>
 * [ReAct] node=reasoning event=start iteration=2 traceId=abc123
 * [ReAct] node=reasoning event=complete iteration=2 durationMs=1234 toolCallCount=3
 * [ReAct] node=limit_exceeded event=complete iteration=10 finishReason=max_iterations_reached
 * </pre>
 * <p>
 * Note: this listener is intentionally read-only / log-only. Surfacing
 * graph state to channel-side accumulators (e.g. publishing the resolved
 * {@code FinishReason} to message metadata) lives in {@code FinalAnswerNode}
 * via a {@code finish_reason} GraphEvent — that path goes through the
 * PENDING_EVENTS → StreamDelta pipeline that {@code ChatController.StreamAccumulator}
 * actually consumes. A sibling sink that called {@code streamTracker.broadcastObject}
 * here would only reach the browser SSE bus and bypass the accumulator entirely.
 *
 * @author MateClaw Team
 */
@Slf4j
public class ReActLifecycleListener implements GraphLifecycleListener {

    /** 记录每个节点的开始时间，key = nodeId + threadId */
    private final ConcurrentHashMap<String, Long> nodeStartTimes = new ConcurrentHashMap<>();

    @Override
    public void onStart(String nodeId, Map<String, Object> state, RunnableConfig config) {
        String traceId = getStringValue(state, TRACE_ID);
        int iteration = getIntValue(state, CURRENT_ITERATION);

        log.info("[ReAct] node={} event=start iteration={} traceId={}",
                nodeId, iteration, traceId);
    }

    @Override
    public void before(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        String key = nodeId + ":" + Thread.currentThread().getId();
        nodeStartTimes.put(key, curTime != null ? curTime : System.currentTimeMillis());
    }

    @Override
    public void after(String nodeId, Map<String, Object> state, RunnableConfig config, Long curTime) {
        String key = nodeId + ":" + Thread.currentThread().getId();
        Long startTime = nodeStartTimes.remove(key);
        long durationMs = 0;
        if (startTime != null && curTime != null) {
            durationMs = curTime - startTime;
        }

        int iteration = getIntValue(state, CURRENT_ITERATION);
        int toolCallCount = getIntValue(state, TOOL_CALL_COUNT);
        String traceId = getStringValue(state, TRACE_ID);
        String finishReason = getStringValue(state, FINISH_REASON);
        boolean shouldSummarize = getBooleanValue(state, SHOULD_SUMMARIZE);

        // 结构化日志
        StringBuilder logMsg = new StringBuilder();
        logMsg.append(String.format("[ReAct] node=%s event=complete iteration=%d durationMs=%d",
                nodeId, iteration, durationMs));
        logMsg.append(String.format(" toolCallCount=%d", toolCallCount));
        if (!traceId.isEmpty()) {
            logMsg.append(String.format(" traceId=%s", traceId));
        }
        if (!finishReason.isEmpty()) {
            logMsg.append(String.format(" finishReason=%s", finishReason));
        }
        if (shouldSummarize) {
            logMsg.append(" shouldSummarize=true");
        }

        // 观察历史大小
        Object obsHistory = state.get(OBSERVATION_HISTORY);
        if (obsHistory instanceof List<?> list) {
            logMsg.append(String.format(" observationCount=%d", list.size()));
        }

        log.info(logMsg.toString());
    }

    @Override
    public void onError(String nodeId, Map<String, Object> state, Throwable ex, RunnableConfig config) {
        String traceId = getStringValue(state, TRACE_ID);
        int iteration = getIntValue(state, CURRENT_ITERATION);

        log.error("[ReAct] node={} event=error iteration={} traceId={} error={}",
                nodeId, iteration, traceId, ex.getMessage(), ex);
    }

    @Override
    public void onComplete(String nodeId, Map<String, Object> state, RunnableConfig config) {
        String finishReason = getStringValue(state, FINISH_REASON);
        String traceId = getStringValue(state, TRACE_ID);
        int iteration = getIntValue(state, CURRENT_ITERATION);
        int toolCallCount = getIntValue(state, TOOL_CALL_COUNT);
        boolean limitExceeded = getBooleanValue(state, LIMIT_EXCEEDED);

        if (FINAL_ANSWER_NODE.equals(nodeId)) {
            log.info("[ReAct] graph=complete node={} iteration={} toolCallCount={} " +
                            "finishReason={} limitExceeded={} traceId={}",
                    nodeId, iteration, toolCallCount, finishReason, limitExceeded, traceId);
        }
    }

    // ===== 安全取值工具方法 =====

    private static String getStringValue(Map<String, Object> state, String key) {
        Object val = state.get(key);
        return val instanceof String s ? s : "";
    }

    private static int getIntValue(Map<String, Object> state, String key) {
        Object val = state.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return 0;
    }

    private static boolean getBooleanValue(Map<String, Object> state, String key) {
        Object val = state.get(key);
        return val instanceof Boolean b && b;
    }
}
