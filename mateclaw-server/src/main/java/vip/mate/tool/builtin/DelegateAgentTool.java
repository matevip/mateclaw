package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Built-in tool: Agent delegation (multi-agent collaboration).
 * <p>
 * Two modes:
 * <ul>
 *   <li>{@link #delegateToAgent} — single-task serial delegation</li>
 *   <li>{@link #delegateParallel} — parallel delegation to up to 3 child agents simultaneously</li>
 * </ul>
 * Each delegated agent runs in an isolated child conversation (parent-child relationship is
 * persisted). Progress is relayed to the parent session via SSE events in real time.
 * Child agents have a narrowed tool set — recursive delegation and agent-discovery tools are
 * blocked.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelegateAgentTool {

    private static final int MAX_DELEGATION_DEPTH = 3;
    private static final int MAX_RESULT_LENGTH = 4000;
    private static final int MAX_PARALLEL_CHILDREN = 3;
    /**
     * Per-child timeout — raised from 60 s to 120 s so that slow LLM models
     * (kimi-code observed p99 ≈ 91 s) can complete before the parent gives up.
     * The previous 60 s limit was structurally impossible to satisfy once any
     * child called an LLM-backed tool.
     */
    private static final int PARALLEL_TIMEOUT_SECONDS = 120;

    /** Tools blocked for child agents — prevents recursion and side effects. */
    private static final Set<String> CHILD_DENIED_TOOLS = Set.of(
            "delegateToAgent",      // no recursive serial delegation
            "delegateParallel",     // no recursive parallel delegation
            "listAvailableAgents"   // child agents do not need to discover other agents
    );

    /** Executor for parallel delegation — one JDK 21 virtual thread per child agent. */
    private static final ExecutorService DELEGATION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final ChatStreamTracker streamTracker;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    // ==================== Single-task delegation ====================

    @vip.mate.tool.ConcurrencyUnsafe("spawns a child agent session and writes to mate_conversation; serialize to keep session graph deterministic")
    @Tool(description = """
            Delegate a task to another Agent for multi-agent collaboration. \
            Target Agent executes in an independent session and returns its final reply. \
            Parent receives real-time progress updates during execution. \
            For multiple parallel tasks, use delegateParallel instead.""")
    public String delegateToAgent(
            @ToolParam(description = "Target Agent name (exact match)") String agentName,
            @ToolParam(description = "Task description with complete context information") String task) {

        if (agentName == null || agentName.isBlank()) {
            return "[错误] 请指定目标 Agent 名称。" + availableAgentsHint();
        }
        if (task == null || task.isBlank()) {
            return "[错误] 请提供任务描述。";
        }

        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "[错误] 委派层级已达上限（" + MAX_DELEGATION_DEPTH + " 层），请直接处理任务。";
        }

        AgentEntity target = findAgent(agentName);
        if (target == null) {
            return "[错误] 未找到名为「" + agentName + "」的已启用 Agent。" + availableAgentsHint();
        }

        String parentConversationId = resolveParentConversationId();
        String childConversationId = createChildConv(target, parentConversationId);

        log.info("Agent delegation: depth={}, target={}({}), childConv={}, parentConv={}",
                depth + 1, target.getName(), target.getId(), childConversationId, parentConversationId);

        // Broadcast delegation_start + register event relay to parent session
        boolean hasParent = parentConversationId != null && streamTracker.isRunning(parentConversationId);
        if (hasParent) {
            streamTracker.broadcastObject(parentConversationId, "delegation_start", Map.of(
                    "childConversationId", childConversationId,
                    "childAgentName", target.getName(),
                    "task", truncate(task, 200)));
        }
        Runnable stopRelay = hasParent ? registerRelay(childConversationId, parentConversationId, target.getName()) : null;

        // Execute child agent
        ChildResult result = runSingleChild(0, target, task, parentConversationId, childConversationId);

        // Cleanup relay, then broadcast final result
        if (stopRelay != null) stopRelay.run();
        if (hasParent) {
            broadcastEnd(parentConversationId, childConversationId, target.getName(), result);
        }

        return result.toToolResponse(target.getName());
    }

    // ==================== Parallel delegation ====================

    @vip.mate.tool.ConcurrencyUnsafe("internally fans out to its own thread pool; outer executor must not double-parallelize")
    @Tool(description = """
            Delegate multiple tasks to different Agents in parallel (max 3). \
            Each task runs concurrently in an independent child session. \
            Use this when you have multiple independent sub-tasks that can run simultaneously. \
            Input is a JSON array: [{"agentName":"Agent名称","task":"任务描述"}, ...]""")
    public String delegateParallel(
            @ToolParam(description = "JSON array of tasks: [{\"agentName\":\"X\",\"task\":\"Y\"}, ...]")
            String tasksJson) {

        // 1. Parse task list
        List<Map<String, String>> tasks;
        try {
            tasks = objectMapper.readValue(tasksJson, new TypeReference<>() {});
        } catch (Exception e) {
            return "[错误] 无法解析任务 JSON：" + e.getMessage() + "\n格式: [{\"agentName\":\"X\",\"task\":\"Y\"}]";
        }

        if (tasks == null || tasks.isEmpty()) {
            return "[错误] 任务列表为空。";
        }
        if (tasks.size() > MAX_PARALLEL_CHILDREN) {
            return "[错误] 最多支持 " + MAX_PARALLEL_CHILDREN + " 个并行任务，当前 " + tasks.size() + " 个。";
        }

        int depth = DelegationContext.currentDepth();
        if (depth >= MAX_DELEGATION_DEPTH) {
            return "[错误] 委派层级已达上限（" + MAX_DELEGATION_DEPTH + " 层），请直接处理任务。";
        }

        String parentConversationId = resolveParentConversationId();
        boolean hasParent = parentConversationId != null && streamTracker.isRunning(parentConversationId);

        // 2. Main thread: validate agents, create child conversations, register relays
        record PreparedChild(int index, AgentEntity agent, String task, String childConvId, Runnable stopRelay) {}
        List<PreparedChild> prepared = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < tasks.size(); i++) {
            Map<String, String> t = tasks.get(i);
            String agentName = t.get("agentName");
            String task = t.get("task");

            if (agentName == null || agentName.isBlank() || task == null || task.isBlank()) {
                errors.add("[任务 " + (i + 1) + "] agentName 或 task 为空");
                continue;
            }

            AgentEntity agent = findAgent(agentName);
            if (agent == null) {
                errors.add("[任务 " + (i + 1) + "] 未找到 Agent「" + agentName + "」");
                continue;
            }

            String childConvId = createChildConv(agent, parentConversationId);
            Runnable stopRelay = hasParent ? registerRelay(childConvId, parentConversationId, agent.getName()) : null;
            prepared.add(new PreparedChild(i, agent, task, childConvId, stopRelay));
        }

        if (prepared.isEmpty()) {
            return "[错误] 所有任务校验失败：\n" + String.join("\n", errors);
        }

        log.info("Parallel delegation: {} tasks, parentConv={}", prepared.size(), parentConversationId);

        // 3. Broadcast delegation_start (parallel mode)
        if (hasParent) {
            List<Map<String, String>> childrenInfo = prepared.stream().map(p -> Map.of(
                    "childConversationId", p.childConvId,
                    "childAgentName", p.agent.getName(),
                    "task", truncate(p.task, 100)
            )).toList();
            streamTracker.broadcastObject(parentConversationId, "delegation_start", Map.of(
                    "parallel", true,
                    "children", childrenInfo));
        }

        // 4. Fan out — execute children in parallel
        long startTime = System.currentTimeMillis();
        Map<Integer, CompletableFuture<ChildResult>> futures = new LinkedHashMap<>();

        for (PreparedChild p : prepared) {
            CompletableFuture<ChildResult> future = CompletableFuture.supplyAsync(
                    () -> runSingleChild(p.index, p.agent, p.task, parentConversationId, p.childConvId),
                    DELEGATION_EXECUTOR);

            // Broadcast per-child completion as soon as each child finishes
            // — frontend can update that child's status without waiting for all children.
            // Guard: skip CancellationException (fired when the timeout loop calls cancel(true))
            // because the timeout result is already handled in the collection loop below and
            // emitting here first would race-replace the correct "timeout" error before delegation_end
            // has a chance to patch remaining running segments.
            if (hasParent) {
                final String parentConvIdFinal = parentConversationId;
                future.whenComplete((result, ex) -> {
                    if (ex instanceof java.util.concurrent.CancellationException) return;
                    if (!streamTracker.isRunning(parentConvIdFinal)) return;
                    ChildResult r = (result != null) ? result
                            : ChildResult.ofError(p.index, p.agent.getName(),
                                    ex != null ? ex.getMessage() : "Unknown error");
                    Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("taskIndex", r.taskIndex);
                    payload.put("childConversationId", p.childConvId);
                    payload.put("childAgentName", r.agentName);
                    payload.put("success", r.success);
                    payload.put("outcome", r.outcome);
                    payload.put("rawLength", r.rawLength);
                    payload.put("trimmedLength", r.trimmedLength);
                    payload.put("blank", r.isBlank());
                    payload.put("durationMs", r.durationMs);
                    payload.put("resultPreview", r.success
                            ? truncate(r.result, 400)
                            : (r.error != null ? r.error : "error"));
                    streamTracker.broadcastObject(parentConvIdFinal, "delegation_child_complete", payload);
                });
            }

            futures.put(p.index, future);
        }

        // 5. Wait for all children (with timeout)
        List<ChildResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Parallel delegation timed out ({}s), collecting completed results", PARALLEL_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("Parallel delegation error: {}", e.getMessage());
        }

        // Collect results — completed futures get their value; unfinished ones are cancelled and recorded as timeout
        for (var entry : futures.entrySet()) {
            int idx = entry.getKey();
            CompletableFuture<ChildResult> f = entry.getValue();
            PreparedChild p = prepared.stream().filter(pp -> pp.index == idx).findFirst().orElse(null);
            String agentName = p != null ? p.agent.getName() : "Unknown";

            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    results.add(f.get());
                } catch (Exception ex) {
                    results.add(ChildResult.ofError(idx, agentName, ex.getMessage()));
                }
            } else {
                f.cancel(true);
                // Use ofTimeout so outcome="timeout" is explicit and distinct from "error".
                results.add(ChildResult.ofTimeout(idx, agentName, PARALLEL_TIMEOUT_SECONDS));
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // 6. Stop all relays
        for (PreparedChild p : prepared) {
            if (p.stopRelay != null) p.stopRelay.run();
        }

        // 7. Broadcast delegation_end with per-child structured summary
        if (hasParent) {
            List<Map<String, Object>> childResults = results.stream().map(r -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("taskIndex", r.taskIndex);
                m.put("agentName", r.agentName);
                m.put("success", r.success);
                m.put("outcome", r.outcome);           // "success"|"blank_success"|"timeout"|"error"
                m.put("rawLength", r.rawLength);        // chars before truncation
                m.put("trimmedLength", r.trimmedLength);
                m.put("blank", r.isBlank());
                m.put("durationMs", r.durationMs);
                // childConversationId for stable frontend segment lookup
                prepared.stream()
                        .filter(p -> p.index == r.taskIndex)
                        .findFirst()
                        .ifPresent(p -> m.put("childConversationId", p.childConvId));
                if (!r.success && r.error != null) m.put("error", r.error);
                return m;
            }).toList();
            streamTracker.broadcastObject(parentConversationId, "delegation_end", Map.of(
                    "parallel", true,
                    "totalDurationMs", totalDurationMs,
                    "success", results.stream().allMatch(r -> r.success),
                    "completedCount", results.stream().filter(r -> r.success).count(),
                    "blankCount", results.stream().filter(ChildResult::isBlank).count(),
                    "totalCount", results.size(),
                    "childResults", childResults));
        }

        // 8. Build return text — structured so the parent LLM cannot misread current results
        //    using memory of past timeouts. The machine-readable header line is the source of truth.
        results.sort(Comparator.comparingInt(r -> r.taskIndex));
        long successCount = results.stream().filter(r -> r.success && !r.isBlank()).count();
        long blankCount = results.stream().filter(ChildResult::isBlank).count();
        long timeoutCount = results.stream().filter(r -> "timeout".equals(r.outcome)).count();
        long errorCount = results.stream().filter(r -> "error".equals(r.outcome)).count();

        StringBuilder sb = new StringBuilder();

        // Machine-readable summary line (highest priority, appears first).
        // Explicit blank/timeout/error counts prevent the parent agent from misreading a
        // successful run as a timeout even when historical memory says "this agent often times out".
        sb.append("[PARALLEL_DELEGATION_RESULT]")
          .append(" total=").append(results.size())
          .append(" success=").append(successCount)
          .append(" blank_success=").append(blankCount)
          .append(" timeout=").append(timeoutCount)
          .append(" error=").append(errorCount)
          .append(" durationMs=").append(totalDurationMs)
          .append("\n\n");

        // Important: this result is from the current execution. Any timeout entries in the
        // conversation history were from previous runs and must not be applied to this result.
        sb.append("⚠ 注意：本次结果基于当前执行，与历史对话中出现的超时记录无关。\n\n");

        if (!errors.isEmpty()) {
            sb.append("⚠️ 部分任务未执行（Agent 未找到或参数错误）：\n");
            errors.forEach(e -> sb.append("  ").append(e).append("\n"));
            sb.append("\n");
        }

        sb.append("## 各子任务执行结果\n\n");
        for (ChildResult r : results) {
            sb.append("### [任务 ").append(r.taskIndex + 1).append("] ").append(r.agentName).append("\n");
            // Per-row machine-readable status — impossible to confuse with a different outcome
            sb.append("outcome=").append(r.outcome)
              .append(" | contentLength=").append(r.trimmedLength).append("chars")
              .append(" | rawLength=").append(r.rawLength).append("chars")
              .append(" | duration=").append(r.durationMs / 1000).append("s")
              .append("\n\n");

            switch (r.outcome) {
                case "success" -> {
                    sb.append("✅ 执行成功，有实质内容（").append(r.trimmedLength).append(" 字符）\n\n");
                    sb.append(r.result);
                }
                case "blank_success" -> {
                    sb.append("⚠ 执行成功，但返回内容为空（rawLength=").append(r.rawLength)
                      .append("，trim 后 0 字符）。请勿将此误报为超时或失败——子 Agent 已正常完成，只是本次无输出。\n");
                }
                case "timeout" ->
                    sb.append("❌ 超时（").append(PARALLEL_TIMEOUT_SECONDS).append("s 内未返回）\n");
                default ->
                    sb.append("❌ 失败：").append(r.error).append("\n");
            }
            sb.append("\n");
        }
        return truncate(sb.toString(), MAX_RESULT_LENGTH * 2); // 并行结果允许更长
    }

    // ==================== Child agent execution (shared by single and parallel paths) ====================

    /**
     * Runs a single child agent. Sets up {@link DelegationContext} independently per virtual thread
     * so that parallel children do not share ThreadLocal state.
     * <p>
     * Raw result length must be measured <em>before</em> calling {@code truncate()}, otherwise
     * {@link ChildResult#rawLength} and {@link ChildResult#trimmedLength} would always reflect the
     * truncated length, making "blank_success" detection unreliable.
     */
    private ChildResult runSingleChild(int taskIndex, AgentEntity target, String task,
                                        String parentConversationId, String childConversationId) {
        DelegationContext.enter(parentConversationId, CHILD_DENIED_TOOLS);
        try {
            long startTime = System.currentTimeMillis();
            String rawResult = agentService.chat(target.getId(), task, childConversationId);
            long durationMs = System.currentTimeMillis() - startTime;
            // Measure lengths before truncation so ChildResult carries accurate metadata.
            return ChildResult.ofSuccess(taskIndex, target.getName(), rawResult, durationMs,
                    MAX_RESULT_LENGTH);
        } catch (Exception e) {
            log.error("Child agent failed: taskIndex={}, agent={}, error={}",
                    taskIndex, target.getName(), e.getMessage());
            return ChildResult.ofError(taskIndex, target.getName(), e.getMessage());
        } finally {
            DelegationContext.exit();
        }
    }

    /**
     * Result carrier for a single child agent execution.
     *
     * <p>{@code outcome} values:
     * <ul>
     *   <li>{@code "success"} — completed successfully with non-empty content (trimmedLength > 0)</li>
     *   <li>{@code "blank_success"} — completed successfully but returned empty content (trimmedLength == 0)</li>
     *   <li>{@code "timeout"} — did not complete within the parallel wait window</li>
     *   <li>{@code "error"} — threw an exception during execution</li>
     * </ul>
     *
     * <p>{@code rawLength} and {@code trimmedLength} are measured before truncation and reflect the
     * true content length.
     */
    private record ChildResult(
            int taskIndex, String agentName, boolean success,
            String result, String error, long durationMs,
            /** "success" | "blank_success" | "timeout" | "error" */
            String outcome,
            int rawLength, int trimmedLength) {

        /** Whether the child returned no usable content (blank_success). */
        boolean isBlank() { return "blank_success".equals(outcome); }

        /**
         * Factory for a successful child execution.
         * Measures lengths from the raw result before applying the truncation limit.
         */
        static ChildResult ofSuccess(int idx, String name, String rawResult, long ms, int maxLen) {
            String safe = rawResult != null ? rawResult : "";
            String trimmed = safe.trim();
            boolean blank = trimmed.isEmpty();
            return new ChildResult(
                    idx, name, true,
                    truncate(safe, maxLen),
                    null, ms,
                    blank ? "blank_success" : "success",
                    safe.length(), trimmed.length());
        }

        /**
         * Factory for a child that failed (exception or timeout).
         * Detects timeout by inspecting the error message so callers don't need to branch.
         */
        static ChildResult ofError(int idx, String name, String err) {
            String msg = err != null ? err : "Unknown error";
            boolean isTimeout = msg.contains("超时") || msg.toLowerCase().contains("timeout");
            return new ChildResult(idx, name, false, null, msg, 0,
                    isTimeout ? "timeout" : "error", 0, 0);
        }

        /** Factory for an explicit timeout (parallel window exceeded). */
        static ChildResult ofTimeout(int idx, String name, int timeoutSec) {
            String msg = "超时 (" + timeoutSec + "s)";
            return new ChildResult(idx, name, false, null, msg, (long) timeoutSec * 1000L,
                    "timeout", 0, 0);
        }

        // Legacy shims — kept for callers that pre-date the factory methods
        static ChildResult success(int idx, String name, String result, long ms) {
            // result may already be truncated at call site — lengths will be approximate
            String safe = result != null ? result : "";
            String trimmed = safe.trim();
            boolean blank = trimmed.isEmpty();
            return new ChildResult(idx, name, true, safe, null, ms,
                    blank ? "blank_success" : "success", safe.length(), trimmed.length());
        }
        static ChildResult error(int idx, String name, String err) {
            return ofError(idx, name, err);
        }

        String toToolResponse(String agentName) {
            if (success) return "[Agent「" + agentName + "」的回复]\n\n" + (result != null ? result : "");
            return "[错误] Agent「" + agentName + "」执行失败: " + error;
        }

        private static String truncate(String text, int maxLength) {
            if (text == null) return "";
            if (text.length() <= maxLength) return text;
            return text.substring(0, maxLength) + "\n... [截断，原文 " + text.length() + " 字符]";
        }
    }

    // ==================== Helper methods ====================

    @Tool(description = "List all available Agents (enabled), including name, type, and description.")
    public String listAvailableAgents() {
        List<AgentEntity> agents = agentMapper.selectList(
                new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getEnabled, true)
                        .orderByAsc(AgentEntity::getName));
        if (agents.isEmpty()) return "当前没有可用的 Agent。";
        StringBuilder sb = new StringBuilder("可用 Agent 列表：\n\n");
        for (AgentEntity agent : agents) {
            sb.append("- **").append(agent.getName()).append("**");
            sb.append(" (").append(agent.getAgentType()).append(")");
            if (agent.getDescription() != null && !agent.getDescription().isBlank()) {
                sb.append(" — ").append(agent.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private AgentEntity findAgent(String name) {
        return agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getName, name.trim())
                .eq(AgentEntity::getEnabled, true));
    }

    private String createChildConv(AgentEntity target, String parentConversationId) {
        String childConvId = "child-" + UUID.randomUUID().toString().substring(0, 12);
        try {
            conversationService.createChildConversation(childConvId, target.getId(), "system",
                    target.getWorkspaceId() != null ? target.getWorkspaceId() : 1L, parentConversationId);
        } catch (Exception e) {
            log.warn("Failed to create child conversation: {}", e.getMessage());
        }
        return childConvId;
    }

    private Runnable registerRelay(String childConvId, String parentConvId, String childAgentName) {
        return streamTracker.addEventRelay(childConvId, (eventName, jsonData) -> {
            if ("tool_call_started".equals(eventName) || "tool_call_completed".equals(eventName) || "phase".equals(eventName)) {
                try {
                    // Parse jsonData into a plain Object so the frontend receives a proper
                    // JSON object under "data", not a string containing serialized JSON.
                    // If parsing fails (e.g. plain text payload), fall back to the raw string.
                    Object parsedData;
                    try {
                        parsedData = objectMapper.readValue(jsonData, Object.class);
                    } catch (Exception ignored) {
                        parsedData = jsonData;
                    }
                    streamTracker.broadcastObject(parentConvId, "delegation_progress", Map.of(
                            "childConversationId", childConvId,
                            "childAgentName", childAgentName,
                            "originalEvent", eventName,
                            "data", parsedData));
                } catch (Exception e) {
                    log.debug("Relay error: {}", e.getMessage());
                }
            }
        });
    }

    private void broadcastEnd(String parentConvId, String childConvId, String agentName, ChildResult result) {
        streamTracker.broadcastObject(parentConvId, "delegation_end", Map.of(
                "childConversationId", childConvId,
                "childAgentName", agentName,
                "success", result.success,
                "durationMs", result.durationMs,
                "resultPreview", result.success ? truncate(result.result, 200) : (result.error != null ? result.error : "")
        ));
    }

    private String resolveParentConversationId() {
        try {
            String ctxConvId = ToolExecutionContext.conversationId();
            if (ctxConvId != null && !ctxConvId.isBlank()) return ctxConvId;
        } catch (Exception ignored) {}
        return DelegationContext.parentConversationId();
    }

    private String availableAgentsHint() {
        List<AgentEntity> agents = agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getEnabled, true).select(AgentEntity::getName));
        if (agents.isEmpty()) return "";
        return "\n可用 Agent: " + agents.stream().map(AgentEntity::getName).collect(Collectors.joining("、"));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n... [截断，原文 " + text.length() + " 字符]";
    }
}
