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
 * 内置工具：Agent 委派（多 Agent 协作）
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>{@link #delegateToAgent} — 单任务委派（串行）</li>
 *   <li>{@link #delegateParallel} — 多任务并行委派（最多 3 个子 Agent 同时执行）</li>
 * </ul>
 * 被委派的 Agent 在独立子会话中运行（记录父子关系），
 * 执行期间通过 SSE 事件 relay 向父会话实时推送进度。
 * 子 Agent 的工具集自动收窄——禁止递归委派和 Agent 发现工具。
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
    private static final int PARALLEL_TIMEOUT_SECONDS = 60;

    /** 子 Agent 禁用的工具：防递归 + 防副作用 */
    private static final Set<String> CHILD_DENIED_TOOLS = Set.of(
            "delegateToAgent",      // 禁止递归委派
            "delegateParallel",     // 禁止并行递归
            "listAvailableAgents"   // 子 Agent 不需要发现其他 Agent
    );

    /** 并行委派执行器：JDK 21 虚拟线程，每个子 Agent 一个轻量级虚拟线程 */
    private static final ExecutorService DELEGATION_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AgentService agentService;
    private final AgentMapper agentMapper;
    private final ChatStreamTracker streamTracker;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    // ==================== 单任务委派 ====================

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

        log.info("Agent 委派: depth={}, target={}({}), childConv={}, parentConv={}",
                depth + 1, target.getName(), target.getId(), childConversationId, parentConversationId);

        // SSE 广播 + relay
        boolean hasParent = parentConversationId != null && streamTracker.isRunning(parentConversationId);
        if (hasParent) {
            streamTracker.broadcastObject(parentConversationId, "delegation_start", Map.of(
                    "childConversationId", childConversationId,
                    "childAgentName", target.getName(),
                    "task", truncate(task, 200)));
        }
        Runnable stopRelay = hasParent ? registerRelay(childConversationId, parentConversationId, target.getName()) : null;

        // 执行
        ChildResult result = runSingleChild(0, target, task, parentConversationId, childConversationId);

        // 清理 + 广播结果
        if (stopRelay != null) stopRelay.run();
        if (hasParent) {
            broadcastEnd(parentConversationId, childConversationId, target.getName(), result);
        }

        return result.toToolResponse(target.getName());
    }

    // ==================== 并行委派 ====================

    @vip.mate.tool.ConcurrencyUnsafe("internally fans out to its own thread pool; outer executor must not double-parallelize")
    @Tool(description = """
            Delegate multiple tasks to different Agents in parallel (max 3). \
            Each task runs concurrently in an independent child session. \
            Use this when you have multiple independent sub-tasks that can run simultaneously. \
            Input is a JSON array: [{"agentName":"Agent名称","task":"任务描述"}, ...]""")
    public String delegateParallel(
            @ToolParam(description = "JSON array of tasks: [{\"agentName\":\"X\",\"task\":\"Y\"}, ...]")
            String tasksJson) {

        // 1. 解析任务列表
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

        // 2. 主线程：校验所有 Agent + 创建子会话 + 注册 relay
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

        log.info("并行委派: {} 个任务, parentConv={}", prepared.size(), parentConversationId);

        // 3. 广播 delegation_start（并行模式）
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

        // 4. 并行执行
        long startTime = System.currentTimeMillis();
        Map<Integer, CompletableFuture<ChildResult>> futures = new LinkedHashMap<>();

        for (PreparedChild p : prepared) {
            CompletableFuture<ChildResult> future = CompletableFuture.supplyAsync(
                    () -> runSingleChild(p.index, p.agent, p.task, parentConversationId, p.childConvId),
                    DELEGATION_EXECUTOR);
            futures.put(p.index, future);
        }

        // 5. 等待全部完成（带超时）
        List<ChildResult> results = new ArrayList<>();
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("并行委派超时 ({}s)，收集已完成的结果", PARALLEL_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.error("并行委派异常: {}", e.getMessage());
        }

        // 收集结果（已完成的 + 超时的）
        for (var entry : futures.entrySet()) {
            int idx = entry.getKey();
            CompletableFuture<ChildResult> f = entry.getValue();
            PreparedChild p = prepared.stream().filter(pp -> pp.index == idx).findFirst().orElse(null);
            String agentName = p != null ? p.agent.getName() : "Unknown";

            if (f.isDone() && !f.isCompletedExceptionally()) {
                try {
                    results.add(f.get());
                } catch (Exception ex) {
                    results.add(ChildResult.error(idx, agentName, ex.getMessage()));
                }
            } else {
                f.cancel(true);
                results.add(ChildResult.error(idx, agentName, "超时 (" + PARALLEL_TIMEOUT_SECONDS + "s)"));
            }
        }

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // 6. 清理 relay
        for (PreparedChild p : prepared) {
            if (p.stopRelay != null) p.stopRelay.run();
        }

        // 7. 广播 delegation_end
        if (hasParent) {
            streamTracker.broadcastObject(parentConversationId, "delegation_end", Map.of(
                    "parallel", true,
                    "totalDurationMs", totalDurationMs,
                    "success", results.stream().allMatch(r -> r.success),
                    "completedCount", results.stream().filter(r -> r.success).count(),
                    "totalCount", results.size()));
        }

        // 8. 构建返回结果
        results.sort(Comparator.comparingInt(r -> r.taskIndex));
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("⚠️ 部分任务未执行：\n");
            errors.forEach(e -> sb.append("  ").append(e).append("\n"));
            sb.append("\n");
        }
        sb.append("并行执行 ").append(results.size()).append(" 个任务（总耗时 ")
                .append(totalDurationMs / 1000).append("s）：\n\n");
        for (ChildResult r : results) {
            sb.append("---\n### [任务 ").append(r.taskIndex + 1).append("] Agent「").append(r.agentName).append("」");
            sb.append(r.success ? " ✓" : " ✗").append(" (").append(r.durationMs / 1000).append("s)\n\n");
            sb.append(r.success ? r.result : "[错误] " + r.error).append("\n\n");
        }
        return truncate(sb.toString(), MAX_RESULT_LENGTH * 2); // 并行结果允许更长
    }

    // ==================== 子 Agent 执行（单/并行共用） ====================

    /**
     * 执行单个子 Agent。在子线程内独立设置 DelegationContext，解决 ThreadLocal 并行问题。
     */
    private ChildResult runSingleChild(int taskIndex, AgentEntity target, String task,
                                        String parentConversationId, String childConversationId) {
        DelegationContext.enter(parentConversationId, CHILD_DENIED_TOOLS);
        try {
            long startTime = System.currentTimeMillis();
            String result = agentService.chat(target.getId(), task, childConversationId);
            long durationMs = System.currentTimeMillis() - startTime;
            return ChildResult.success(taskIndex, target.getName(), truncate(result, MAX_RESULT_LENGTH), durationMs);
        } catch (Exception e) {
            log.error("子 Agent 执行失败: taskIndex={}, agent={}, error={}",
                    taskIndex, target.getName(), e.getMessage());
            return ChildResult.error(taskIndex, target.getName(), e.getMessage());
        } finally {
            DelegationContext.exit();
        }
    }

    /** 子 Agent 执行结果 */
    private record ChildResult(int taskIndex, String agentName, boolean success,
                                String result, String error, long durationMs) {
        static ChildResult success(int idx, String name, String result, long ms) {
            return new ChildResult(idx, name, true, result, null, ms);
        }
        static ChildResult error(int idx, String name, String err) {
            return new ChildResult(idx, name, false, null, err != null ? err : "Unknown error", 0);
        }
        String toToolResponse(String agentName) {
            if (success) return "[Agent「" + agentName + "」的回复]\n\n" + result;
            return "[错误] Agent「" + agentName + "」执行失败: " + error;
        }
    }

    // ==================== 辅助方法 ====================

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
                    streamTracker.broadcastObject(parentConvId, "delegation_progress", Map.of(
                            "childConversationId", childConvId,
                            "childAgentName", childAgentName,
                            "originalEvent", eventName,
                            "data", jsonData));
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
