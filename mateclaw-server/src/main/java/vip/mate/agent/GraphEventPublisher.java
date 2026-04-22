package vip.mate.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import vip.mate.agent.graph.state.MateClawStateKeys;

import java.util.List;
import java.util.Map;

/**
 * Graph 事件发布工具
 * <p>
 * 所有方法都是 static，不做状态管理。
 * 节点内部收集 List&lt;GraphEvent&gt;，最终写入 PENDING_EVENTS。
 * StateGraph*Agent 从 NodeOutput 中读取这些事件。
 *
 * @author MateClaw Team
 */
public final class GraphEventPublisher {

    private GraphEventPublisher() {}

    // ===== 事件类型常量 =====
    public static final String EVENT_PHASE = "phase";
    public static final String EVENT_TOOL_START = "tool_call_started";
    public static final String EVENT_TOOL_COMPLETE = "tool_call_completed";
    public static final String EVENT_PLAN_CREATED = "plan_created";
    public static final String EVENT_STEP_STARTED = "plan_step_started";
    public static final String EVENT_STEP_COMPLETED = "plan_step_completed";
    public static final String EVENT_TOOL_APPROVAL_REQUESTED = "tool_approval_requested";
    /** RFC-06 D-6: lightweight performance summary emitted per-phase. */
    public static final String EVENT_PERF_SUMMARY = "perf_summary";

    /**
     * 事件记录
     */
    public record GraphEvent(String type, Map<String, Object> data, long timestamp) {}

    // ===== 静态工厂方法 =====

    public static GraphEvent phase(String phase, Map<String, Object> extra) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.HashMap<>(extra);
        data.put("phase", phase);
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_PHASE, Map.copyOf(data), ts);
    }

    public static GraphEvent toolStart(String toolName, String arguments) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_TOOL_START, Map.of(
                "toolName", toolName,
                "arguments", arguments != null ? arguments : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent toolComplete(String toolName, String result, boolean success) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_TOOL_COMPLETE, Map.of(
                "toolName", toolName,
                "result", result != null ? truncateResult(result) : "",
                "success", success,
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent planCreated(Long planId, List<String> steps) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_PLAN_CREATED, Map.of(
                "planId", planId,
                "steps", steps,
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent stepStarted(int index, String title) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_STEP_STARTED, Map.of(
                "index", index,
                "title", title != null ? title : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent stepCompleted(int index, String result) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_STEP_COMPLETED, Map.of(
                "index", index,
                "result", result != null ? truncateResult(result) : "",
                "timestamp", ts
        ), ts);
    }

    public static GraphEvent toolApprovalRequested(String pendingId, String toolName,
                                                    String arguments, String reason) {
        long ts = System.currentTimeMillis();
        return new GraphEvent(EVENT_TOOL_APPROVAL_REQUESTED, Map.of(
                "pendingId", pendingId,
                "toolName", toolName != null ? toolName : "",
                "arguments", arguments != null ? truncateResult(arguments) : "",
                "reason", reason != null ? reason : "",
                "timestamp", ts
        ), ts);
    }

    /**
     * 增强版审批事件（包含 findings、severity、summary）
     */
    public static GraphEvent toolApprovalRequested(String pendingId, String toolName,
                                                    String arguments, String reason,
                                                    String summary, String maxSeverity,
                                                    List<Map<String, Object>> findings) {
        long ts = System.currentTimeMillis();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pendingId", pendingId);
        data.put("toolName", toolName != null ? toolName : "");
        data.put("arguments", arguments != null ? truncateForBroadcast(arguments) : "");
        data.put("reason", reason != null ? reason : "");
        data.put("summary", summary);
        data.put("maxSeverity", maxSeverity);
        data.put("findings", findings != null ? findings : List.of());
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_TOOL_APPROVAL_REQUESTED, Map.copyOf(data), ts);
    }

    /**
     * RFC-06 D-6: emit a lightweight performance summary for a phase.
     * Consumers (dashboard, audit, _usage_final) can aggregate these
     * to reconstruct per-turn latency profiles without full tracing.
     *
     * @param phase           e.g. "triage", "reasoning", "tool_execution"
     * @param metrics         arbitrary key-value pairs (e.g. "retry_count", "backoff_wait_ms")
     */
    public static GraphEvent perfSummary(String phase, Map<String, Object> metrics) {
        long ts = System.currentTimeMillis();
        Map<String, Object> data = new java.util.HashMap<>(metrics);
        data.put("phase", phase);
        data.put("timestamp", ts);
        return new GraphEvent(EVENT_PERF_SUMMARY, Map.copyOf(data), ts);
    }

    // ===== 提取方法 =====

    /**
     * 从 NodeOutput 中提取 PENDING_EVENTS
     */
    @SuppressWarnings("unchecked")
    public static List<GraphEvent> extractEvents(NodeOutput output) {
        if (output == null || output.state() == null) {
            return List.of();
        }
        return output.state().<List<GraphEvent>>value(MateClawStateKeys.PENDING_EVENTS)
                .orElse(List.of());
    }

    private static String truncateResult(String result) {
        return result.length() > 500 ? result.substring(0, 500) + "..." : result;
    }

    /**
     * 截断字符串用于直推广播（公共方法，供 Node 直接构造广播数据时使用）
     */
    public static String truncateForBroadcast(String text) {
        return truncateResult(text);
    }
}
