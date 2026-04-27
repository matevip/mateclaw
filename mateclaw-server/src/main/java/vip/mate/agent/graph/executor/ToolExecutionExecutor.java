package vip.mate.agent.graph.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.DirectToolOutput;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.ToolExecutionGuardHelper;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardService;

import java.util.*;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * 统一工具执行器（共享于 ActionNode 和 StepExecutionNode）
 * <p>
 * 两阶段执行模型：
 * <ol>
 *   <li><b>Phase 1 — 顺序 Guard + 分段</b>：按原始顺序逐个做 JSON 校验 → ToolGuard → barrier 判定 → callback 查找 + concurrencySafe 分类</li>
 *   <li><b>Phase 2 — 分段并发执行</b>：barrier 之前的 safe 工具并行执行，unsafe 工具独占执行，结果按原始顺序返回</li>
 * </ol>
 * <p>
 * 审批有前序语义：如果第 N 个工具需要审批，第 N+1、N+2 个工具不会执行。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ToolExecutionExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // JDK 21 virtual threads: no blocking stall for I/O-bound tools.
    // Each tool invocation gets its own lightweight carrier thread.
    // Named threads (matching HookDispatcher convention) for log traceability.
    private static final ExecutorService TOOL_EXECUTOR =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("tool-executor-", 0).factory());

    /**
     * Legacy hardcoded unsafe set, kept as a fallback when no
     * {@link vip.mate.tool.ToolConcurrencyRegistry} is wired in (legacy tests,
     * backwards-compatible constructors). New code should annotate the tool
     * method with {@link vip.mate.tool.ConcurrencyUnsafe} instead of editing
     * this list.
     */
    private static final Set<String> DEFAULT_UNSAFE_TOOLS = Set.of(
            "browser_use", "BrowserUseTool", "write_file", "edit_file"
    );

    /**
     * Layer 1 — hard truncation cap applied to every tool result before it
     * reaches ToolResultStorage (Layer 2 spill) or the LLM prompt.
     *
     * <p>Two-level budget chain (RFC-008 / RFC-06 D-5):
     * <pre>
     *   raw tool result
     *     → truncateToolResult(..., MAX_TOOL_RESULT_CHARS=8000)   // Layer 1: hard cap
     *     → persistIfOversized(..., perResultThresholdChars=16000) // Layer 2: spill to disk
     *     → enforceTurnBudget(..., perTurnBudgetChars=32000)      // Layer 3: per-turn aggregate
     * </pre>
     * Layer 1 runs first and is intentionally kept at 8000 to prevent oversized
     * results from inflating the prompt. Layers 2/3 thresholds are configured in
     * {@link ToolResultProperties} and application.yml.
     */
    private static final int MAX_TOOL_RESULT_CHARS = 8000;

    /** 尾部错误模式检测 */
    private static final java.util.regex.Pattern ERROR_TAIL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b(error|exception|traceback|failed|fatal|panic|stack.?trace|errno)\\b");

    /**
     * RFC-052 §2.4: placeholder written into {@code ToolResponseMessage.content}
     * for returnDirect tools. Intentionally English, short, and free of any
     * tool-specific data so it is safe to enter prompt cache and gives the LLM
     * a clear signal that the tool ran (vs failed).
     */
    static final String DIRECT_TOOL_PLACEHOLDER =
            "[Tool result returned directly to user. Content withheld from model context per tool policy.]";

    /**
     * 智能截断工具结果：检测尾部是否含错误信息，动态调整 head/tail 比例。
     * 错误信息在尾部时保留 80% tail，确保 agent 能看到错误原因。
     */
    static String truncateToolResult(String result, int maxChars) {
        if (result == null || result.length() <= maxChars) return result;
        int rawLen = result.length();
        // 检测尾部 2000 字符是否含错误模式
        String tailRegion = result.substring(Math.max(0, rawLen - 2000));
        boolean errorDetected = ERROR_TAIL_PATTERN.matcher(tailRegion).find();
        double headRatio = errorDetected ? 0.2 : 0.4;
        if (errorDetected) {
            log.info("[ToolExecutor] Error pattern detected in tail, preserving 80% tail (headRatio=0.2)");
        }
        int headLen = (int) (maxChars * headRatio);
        int tailLen = maxChars - headLen - 80;
        if (tailLen <= 0) tailLen = maxChars / 2;
        log.info("[ToolExecutor] Truncated tool result from {} to {} chars (headRatio={})",
                rawLen, maxChars, headRatio);
        return result.substring(0, headLen)
                + "\n\n... [结果已截断，原始 " + rawLen + " 字符，保留首尾关键片段] ...\n\n"
                + result.substring(rawLen - tailLen);
    }

    private final Map<String, ToolCallback> toolCallbackMap;
    private final ToolGuardService toolGuardService;
    private final ToolGuard toolGuard; // legacy fallback
    private final ApprovalWorkflowService approvalService;
    private final ChatStreamTracker streamTracker;
    private final vip.mate.config.ToolTimeoutProperties toolTimeoutProperties;
    /** RFC-008 Phase 3 spill store; nullable so legacy constructors keep working. */
    private final ToolResultStorage resultStorage;
    /** RFC-008 Phase 4 metadata-driven concurrency classifier; nullable for legacy constructors. */
    private final vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry;

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, null, null, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, null, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                  ToolResultStorage resultStorage) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, resultStorage, null);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker,
                                  vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                  ToolResultStorage resultStorage,
                                  vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry) {
        this(toolSet, toolGuardService, null, approvalService, streamTracker, toolTimeoutProperties, resultStorage, concurrencyRegistry);
    }

    public ToolExecutionExecutor(AgentToolSet toolSet, ToolGuard toolGuard,
                                  ApprovalWorkflowService approvalService, ChatStreamTracker streamTracker) {
        this.toolCallbackMap = toolSet.callbackByName();
        this.toolGuardService = null;
        this.toolGuard = toolGuard;
        this.approvalService = approvalService;
        this.streamTracker = streamTracker;
        this.toolTimeoutProperties = null;
        this.resultStorage = null;
        this.concurrencyRegistry = null;
    }

    private ToolExecutionExecutor(AgentToolSet toolSet, ToolGuardService toolGuardService,
                                   ToolGuard toolGuard, ApprovalWorkflowService approvalService,
                                   ChatStreamTracker streamTracker,
                                   vip.mate.config.ToolTimeoutProperties toolTimeoutProperties,
                                   ToolResultStorage resultStorage,
                                   vip.mate.tool.ToolConcurrencyRegistry concurrencyRegistry) {
        this.toolCallbackMap = toolSet.callbackByName();
        this.toolGuardService = toolGuardService;
        this.toolGuard = toolGuard;
        this.approvalService = approvalService;
        this.streamTracker = streamTracker;
        this.toolTimeoutProperties = toolTimeoutProperties;
        this.resultStorage = resultStorage;
        this.concurrencyRegistry = concurrencyRegistry;
    }

    private long getToolTimeoutMs(String toolName) {
        if (toolTimeoutProperties != null) {
            return toolTimeoutProperties.getTimeoutSeconds(toolName) * 1000L;
        }
        return 5 * 60 * 1000L; // default 5 min
    }

    /**
     * 执行工具调用列表
     *
     * @param toolCalls      LLM 请求的工具调用列表
     * @param conversationId 会话 ID
     * @param agentId        Agent ID
     * @param isReplay       是否为审批通过后的重放模式（跳过 ToolGuard）
     * @return 执行结果
     */
    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay) {
        return execute(toolCalls, conversationId, agentId, isReplay, "");
    }

    /** 当前执行的 requesterId，传递给 ToolExecutionContext */
    private volatile String currentRequesterId;
    /** 当前工作区活动目录（为空不限制），传递给 ToolExecutionContext */
    private volatile String currentWorkspaceBasePath;

    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay, String requesterId) {
        return execute(toolCalls, conversationId, agentId, isReplay, requesterId, null);
    }

    public ToolExecutionResult execute(List<AssistantMessage.ToolCall> toolCalls,
                                        String conversationId, String agentId,
                                        boolean isReplay, String requesterId,
                                        String workspaceBasePath) {
        this.currentRequesterId = requesterId;
        this.currentWorkspaceBasePath = workspaceBasePath;
        List<ToolResponseMessage.ToolResponse> allResponses = new ArrayList<>();
        List<GraphEventPublisher.GraphEvent> events = Collections.synchronizedList(new ArrayList<>());
        // RFC-052: accumulate full-text outputs from returnDirect tools so the
        // graph can route to FinalAnswerNode without re-entering the LLM.
        List<DirectToolOutput> directOutputs = Collections.synchronizedList(new ArrayList<>());

        events.add(GraphEventPublisher.phase("action", Map.of("toolCount", toolCalls.size())));

        // ═══ Phase 1: 顺序 Guard + 分段 ═══
        List<PreparedToolCall> preparedCalls = new ArrayList<>();
        ApprovalBarrier barrier = null;

        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);
            String toolName = toolCall.name();
            String arguments = toolCall.arguments();

            events.add(GraphEventPublisher.toolStart(toolCall.id(), toolName, arguments));

            // 0. 子会话工具拦截：委派上下文中的子 Agent 禁止调用特定工具
            if (vip.mate.tool.builtin.DelegationContext.currentDepth() > 0) {
                java.util.Set<String> denied = vip.mate.tool.builtin.DelegationContext.childDeniedTools();
                if (denied.contains(toolName)) {
                    String msg = "[安全限制] 子 Agent 不允许使用工具: " + toolName;
                    log.info("[ToolExecutor] Child agent blocked from using tool: {}", toolName);
                    events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, msg, false));
                    allResponses.add(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, msg));
                    continue;
                }
            }

            // 1. JSON 校验
            if (arguments != null && !arguments.isBlank()) {
                try {
                    OBJECT_MAPPER.readTree(arguments);
                } catch (Exception jsonEx) {
                    log.warn("[ToolExecutor] Tool {} arguments invalid/truncated JSON (len={}): {}",
                            toolName, arguments.length(), jsonEx.getMessage());
                    String truncationError = normalizeToolExecutionError(jsonEx);
                    events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, truncationError, false));
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, truncationError));
                    continue;
                }
            }

            // 2. ToolGuard 安全检查（replay 模式跳过）
            if (!isReplay) {
                GuardDecision decision = evaluateGuard(toolCall, toolName, arguments,
                        conversationId, agentId, toolCalls, i, events, requesterId);

                if (decision.blocked) {
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, decision.response));
                    continue;
                }
                if (decision.needsApproval) {
                    // Barrier: 当前工具创建审批，后续工具不执行
                    allResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolName, decision.response));
                    // 标记后续工具为等待审批
                    for (int j = i + 1; j < toolCalls.size(); j++) {
                        AssistantMessage.ToolCall remaining = toolCalls.get(j);
                        allResponses.add(new ToolResponseMessage.ToolResponse(
                                remaining.id(), remaining.name(),
                                "[⏳ 等待审批] 前序工具等待审批中，本工具暂缓执行。"));
                    }
                    barrier = new ApprovalBarrier(decision.pendingId, toolName);
                    break;
                }
            } else {
                log.info("[ToolExecutor] Replay mode: skipping guard for pre-approved tool {}", toolName);
            }

            // 3. Callback 查找（跳过 provider 内置工具，如 Kimi 的 $web_search）
            if (toolName.startsWith("$")) {
                log.info("[ToolExecutor] Skipping provider builtin tool: {}", toolName);
                allResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, "Provider builtin tool executed server-side"));
                continue;
            }
            ToolCallback callback = toolCallbackMap.get(toolName);
            if (callback == null) {
                log.warn("[ToolExecutor] Tool not found: {}", toolName);
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, "Tool not found: " + toolName, false));
                allResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, "Tool not found: " + toolName));
                continue;
            }

            // 4. 分类: concurrencySafe
            boolean safe = isConcurrencySafe(toolName);
            preparedCalls.add(new PreparedToolCall(toolCall, callback, arguments, safe, allResponses.size(),
                    conversationId, currentRequesterId, currentWorkspaceBasePath));
            // 占位，Phase 2 填充
            allResponses.add(null);
        }

        // ═══ Phase 2: 分段并发执行 ═══
        if (!preparedCalls.isEmpty()) {
            executePreparedCalls(preparedCalls, allResponses, events, directOutputs);
        }

        // Defensive: drop null placeholders (should never appear in practice).
        allResponses.removeIf(Objects::isNull);

        // RFC-008 Phase 3 Layer 3: enforce per-turn aggregate budget across all
        // tool responses for this assistant turn. Spills the largest non-spilled
        // response in turn until the cumulative size fits the budget.
        if (resultStorage != null && !allResponses.isEmpty()) {
            allResponses = new ArrayList<>(resultStorage.enforceTurnBudget(
                    allResponses, conversationId, currentWorkspaceBasePath));
        }

        boolean hasApprovalPending = barrier != null;
        return new ToolExecutionResult(allResponses, events, hasApprovalPending,
                barrier != null ? barrier.pendingId : null,
                barrier != null ? barrier.toolName : null,
                List.copyOf(directOutputs));
    }

    /**
     * Execute a pre-approved tool call (used by StepExecutionNode's replay path
     * after a user approves a previously-blocked invocation).
     *
     * @param conversationId required for per-conversation spill scoping; when
     *     blank, spill files would land in a shared {@code unknown/} directory
     *     and break per-conversation cleanup.
     * @param workspaceBasePath optional; when blank, spill falls back to tmp.
     */
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events,
            String conversationId, String workspaceBasePath) {
        return executePreApproved(toolCall, storedArguments, events, conversationId,
                workspaceBasePath, null);
    }

    /**
     * RFC-052 PR-2: returnDirect-aware variant. When the pre-approved tool
     * declares {@code returnDirect=true}, the result is captured into
     * {@code directOutputs} (verbatim), the SSE consumer gets a
     * {@code tool_direct_result} event, and the {@link ToolResponseMessage}
     * carries the placeholder so any subsequent LLM call can never see the
     * full payload.
     *
     * @param directOutputs nullable; pass-through for callers that don't track
     *     direct outputs (kept for legacy compatibility).
     */
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events,
            String conversationId, String workspaceBasePath,
            List<DirectToolOutput> directOutputs) {
        String toolName = toolCall.name();
        String callArguments = storedArguments != null ? storedArguments : toolCall.arguments();

        ToolCallback callback = toolCallbackMap.get(toolName);
        if (callback == null) {
            log.warn("[ToolExecutor] Pre-approved tool not found: {}", toolName);
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, "Tool not found: " + toolName, false));
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, "Tool not found: " + toolName);
        }

        try {
            log.info("[ToolExecutor] Executing pre-approved tool: {}", toolName);
            String result = callback.call(callArguments);
            int rawLen = result != null ? result.length() : 0;

            // RFC-052: pre-approved tool may itself be returnDirect — in that
            // case its result must take the direct path (no spill, no LLM).
            // Without this branch, an approved direct tool would leak its full
            // content into the next LLM round-trip via the ToolResponseMessage.
            if (isReturnDirect(callback)) {
                String fullResult = result != null ? result : "";
                log.info("[ToolExecutor] Pre-approved tool {} is returnDirect; bypassing " +
                        "spill/truncate, broadcasting tool_direct_result ({} chars)", toolName, rawLen);
                if (directOutputs != null) {
                    directOutputs.add(new DirectToolOutput(
                            toolCall.id(), toolName, fullResult, System.currentTimeMillis()));
                }
                events.add(GraphEventPublisher.toolDirectResult(
                        toolCall.id(), toolName, fullResult));
                return new ToolResponseMessage.ToolResponse(
                        toolCall.id(), toolName, DIRECT_TOOL_PLACEHOLDER);
            }

            // Phase 3 Layer 2: spill before truncation when storage is wired.
            // Use the caller-supplied conversationId so spill files inherit the
            // same per-conversation directory layout as the non-replay path.
            if (resultStorage != null && result != null) {
                String spillConv = conversationId != null && !conversationId.isEmpty() ? conversationId : "unknown";
                result = resultStorage.persistIfOversized(
                        result, toolName, toolCall.id(), spillConv, workspaceBasePath);
            }
            result = truncateToolResult(result, MAX_TOOL_RESULT_CHARS);
            log.info("[ToolExecutor] Pre-approved tool {} returned {} chars{}", toolName, rawLen,
                    result != null && result.length() < rawLen ? " (now " + result.length() + " after spill/truncate)" : "");
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, result, true));
            return new ToolResponseMessage.ToolResponse(
                    toolCall.id(), toolName, result != null ? result : "");
        } catch (Exception e) {
            log.error("[ToolExecutor] Pre-approved tool {} failed: {}", toolName, e.getMessage());
            String safeError = isReturnDirect(callback)
                    ? "Tool execution failed (details withheld per returnDirect policy)"
                    : "Tool execution failed: " + e.getMessage();
            events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, safeError, false));
            return new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, safeError);
        }
    }

    /**
     * Backwards-compatible overload — replay spills land in a synthetic
     * {@code unknown/} conversation bucket. New callers must use the
     * {@link #executePreApproved(AssistantMessage.ToolCall, String, List, String, String)}
     * variant so spill files are correctly scoped per conversation.
     *
     * @deprecated use the 5-arg overload with explicit {@code conversationId}
     */
    @Deprecated
    public ToolResponseMessage.ToolResponse executePreApproved(
            AssistantMessage.ToolCall toolCall, String storedArguments,
            List<GraphEventPublisher.GraphEvent> events) {
        return executePreApproved(toolCall, storedArguments, events, null, currentWorkspaceBasePath);
    }

    // ==================== Phase 2: 并发执行 ====================

    private void executePreparedCalls(List<PreparedToolCall> preparedCalls,
                                       List<ToolResponseMessage.ToolResponse> allResponses,
                                       List<GraphEventPublisher.GraphEvent> events,
                                       List<DirectToolOutput> directOutputs) {
        long execStartMs = System.currentTimeMillis();
        if (!preparedCalls.isEmpty() && streamTracker != null) {
            String conversationId = preparedCalls.get(0).conversationId;
            String phase = classifyBatchPhase(preparedCalls);
            streamTracker.updatePhase(conversationId, phase);
            streamTracker.broadcastObject(conversationId, "phase", GraphEventPublisher.phase(phase, Map.of(
                    "toolCount", preparedCalls.size()
            )).data());
        }
        // 分组: 连续的 safe 工具可以并行，遇到 unsafe 工具则先等待所有 safe 完成再独占执行
        List<List<PreparedToolCall>> batches = buildExecutionBatches(preparedCalls);

        for (List<PreparedToolCall> batch : batches) {
            if (batch.size() == 1) {
                // 单个工具（safe 或 unsafe），直接执行
                PreparedToolCall pc = batch.get(0);
                ToolResponseMessage.ToolResponse response = executeSingleTool(pc, events, directOutputs);
                allResponses.set(pc.resultIndex, response);
            } else {
                // 多个 safe 工具，并行执行
                executeParallelBatch(batch, allResponses, events, directOutputs);
            }
        }

        // D-6: emit tool execution perf summary
        long toolExecMs = System.currentTimeMillis() - execStartMs;
        events.add(GraphEventPublisher.perfSummary("tool_execution", Map.of(
                "tool_exec_ms", toolExecMs,
                "tool_count", preparedCalls.size(),
                "batch_count", batches.size()
        )));
    }

    /**
     * 将 prepared calls 分成执行批次：
     * - 连续的 safe 工具组成一个并行批次
     * - unsafe 工具单独成为一个批次
     */
    private List<List<PreparedToolCall>> buildExecutionBatches(List<PreparedToolCall> preparedCalls) {
        List<List<PreparedToolCall>> batches = new ArrayList<>();
        List<PreparedToolCall> currentSafeBatch = new ArrayList<>();

        for (PreparedToolCall pc : preparedCalls) {
            if (pc.concurrencySafe) {
                currentSafeBatch.add(pc);
            } else {
                // Flush pending safe batch
                if (!currentSafeBatch.isEmpty()) {
                    batches.add(new ArrayList<>(currentSafeBatch));
                    currentSafeBatch.clear();
                }
                // Unsafe tool as solo batch
                batches.add(List.of(pc));
            }
        }
        // Flush remaining safe batch
        if (!currentSafeBatch.isEmpty()) {
            batches.add(currentSafeBatch);
        }
        return batches;
    }

    private void executeParallelBatch(List<PreparedToolCall> batch,
                                       List<ToolResponseMessage.ToolResponse> allResponses,
                                       List<GraphEventPublisher.GraphEvent> events,
                                       List<DirectToolOutput> directOutputs) {
        log.info("[ToolExecutor] Executing {} safe tools in parallel: {}",
                batch.size(), batch.stream().map(pc -> pc.toolCall.name()).toList());
        long batchStartMs = System.currentTimeMillis();

        Map<Integer, CompletableFuture<ToolResponseMessage.ToolResponse>> futures = new LinkedHashMap<>();
        for (PreparedToolCall pc : batch) {
            CompletableFuture<ToolResponseMessage.ToolResponse> future =
                    CompletableFuture.supplyAsync(() -> executeSingleTool(pc, events, directOutputs), TOOL_EXECUTOR);
            futures.put(pc.resultIndex, future);
        }

        // 等待所有并行工具完成，按原始顺序填入结果
        for (var entry : futures.entrySet()) {
            try {
                // 按工具名查找配置的超时时间
                PreparedToolCall matchedPc = batch.stream()
                        .filter(p -> p.resultIndex == entry.getKey()).findFirst().orElse(null);
                long timeoutMs = getToolTimeoutMs(matchedPc != null ? matchedPc.toolCall.name() : null);
                ToolResponseMessage.ToolResponse response = entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS);
                allResponses.set(entry.getKey(), response);
            } catch (Exception e) {
                // 超时或异常 — 填入错误响应
                PreparedToolCall pc = batch.stream()
                        .filter(p -> p.resultIndex == entry.getKey())
                        .findFirst().orElse(null);
                String toolName = pc != null ? pc.toolCall.name() : "unknown";
                String toolId = pc != null ? pc.toolCall.id() : "";
                log.error("[ToolExecutor] Parallel tool {} failed: {}", toolName, e.getMessage());
                allResponses.set(entry.getKey(), new ToolResponseMessage.ToolResponse(
                        toolId, toolName, normalizeToolExecutionError(
                        e instanceof ExecutionException ? (Exception) e.getCause() : (Exception) e)));
            }
        }
        log.info("[ToolExecutor] Parallel batch completed: {} tools in {}ms",
                batch.size(), System.currentTimeMillis() - batchStartMs);
    }

    private ToolResponseMessage.ToolResponse executeSingleTool(PreparedToolCall pc,
                                                                List<GraphEventPublisher.GraphEvent> events,
                                                                List<DirectToolOutput> directOutputs) {
        String toolName = pc.toolCall.name();
        try {
            if (streamTracker != null) {
                streamTracker.updateRunningTool(pc.conversationId, toolName);
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_START,
                        GraphEventPublisher.toolStart(pc.toolCall.id(), toolName, pc.arguments).data());
            }
            log.info("[ToolExecutor] Executing tool: {} with args: {}",
                    toolName, pc.arguments != null && pc.arguments.length() > 200
                            ? pc.arguments.substring(0, 200) + "..." : pc.arguments);

            // 注入工具执行上下文（供 VideoGenerateTool 等获取 conversationId / username / workspaceBasePath）
            ToolExecutionContext.set(pc.conversationId, pc.requesterId, pc.workspaceBasePath);
            String result;
            try {
                result = pc.callback.call(pc.arguments);
            } finally {
                ToolExecutionContext.clear();
            }

            int rawLen = result != null ? result.length() : 0;
            // RFC-052: returnDirect tools bypass spill / truncation / LLM context.
            // Their full text goes to the user verbatim and is never persisted to
            // a workspace cache file (spill could leak sensitive data).
            if (isReturnDirect(pc.callback)) {
                String fullResult = result != null ? result : "";
                log.info("[ToolExecutor] Tool {} is returnDirect; bypassing spill/truncate, " +
                        "broadcasting tool_direct_result ({} chars)", toolName, rawLen);
                directOutputs.add(new DirectToolOutput(
                        pc.toolCall.id(), toolName, fullResult, System.currentTimeMillis()));
                GraphEventPublisher.GraphEvent directEvent =
                        GraphEventPublisher.toolDirectResult(pc.toolCall.id(), toolName, fullResult);
                events.add(directEvent);
                if (streamTracker != null) {
                    streamTracker.broadcastObject(pc.conversationId,
                            GraphEventPublisher.EVENT_TOOL_DIRECT_RESULT, directEvent.data());
                    streamTracker.updateRunningTool(pc.conversationId, null);
                }
                // Placeholder keeps the tool_call_id ↔ tool_response pairing valid
                // for OpenAI-compatible providers, while withholding the data from
                // any subsequent LLM round (the graph won't take a next round —
                // see ObservationDispatcher RETURN_DIRECT_TRIGGERED branch).
                return new ToolResponseMessage.ToolResponse(
                        pc.toolCall.id(), toolName, DIRECT_TOOL_PLACEHOLDER);
            }

            // RFC-008 Phase 3 Layer 2: spill oversized results to disk and replace
            // with preview + path. Falls back to truncation when spilling is
            // disabled or fails. Spill preserves the full output (read_file can
            // retrieve it); truncation discards the tail.
            if (resultStorage != null && result != null) {
                result = resultStorage.persistIfOversized(
                        result, toolName, pc.toolCall.id(), pc.conversationId, pc.workspaceBasePath);
            }
            result = truncateToolResult(result, MAX_TOOL_RESULT_CHARS);
            log.info("[ToolExecutor] Tool {} returned {} chars{}", toolName, rawLen,
                    result != null && result.length() < rawLen ? " (now " + result.length() + " after spill/truncate)" : "");
            events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true));
            if (streamTracker != null) {
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, result, true).data());
                streamTracker.updateRunningTool(pc.conversationId, null);
            }
            return new ToolResponseMessage.ToolResponse(
                    pc.toolCall.id(), toolName, result != null ? result : "");
        } catch (Exception e) {
            log.error("[ToolExecutor] Tool {} execution failed: {}", toolName, e.getMessage(), e);
            // RFC-052: for returnDirect tools, even the error message is
            // suspect — exception text may carry stack traces, SQL fragments,
            // or other sensitive substrings that should not enter LLM context.
            // Emit a generic placeholder instead. Full error still goes to logs
            // for operator diagnosis.
            String reportedError = isReturnDirect(pc.callback)
                    ? "Tool execution failed (details withheld per returnDirect policy)"
                    : normalizeToolExecutionError(e);
            events.add(GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false));
            if (streamTracker != null) {
                streamTracker.broadcastObject(pc.conversationId, GraphEventPublisher.EVENT_TOOL_COMPLETE,
                        GraphEventPublisher.toolComplete(pc.toolCall.id(), toolName, reportedError, false).data());
                streamTracker.updateRunningTool(pc.conversationId, null);
            }
            return new ToolResponseMessage.ToolResponse(
                    pc.toolCall.id(), toolName, reportedError);
        }
    }

    // ==================== Guard 评估 ====================

    private GuardDecision evaluateGuard(AssistantMessage.ToolCall toolCall, String toolName, String arguments,
                                         String conversationId, String agentId,
                                         List<AssistantMessage.ToolCall> allToolCalls, int currentIndex,
                                         List<GraphEventPublisher.GraphEvent> events, String requesterId) {
        ToolInvocationContext guardCtx = ToolInvocationContext.of(toolName, arguments, conversationId, agentId);

        if (toolGuardService != null) {
            GuardEvaluation evaluation = toolGuardService.evaluate(guardCtx);

            if (evaluation.shouldBlock()) {
                log.warn("[ToolExecutor] Tool call BLOCKED: tool={}, summary={}", toolName, evaluation.summary());
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, evaluation.summary(), false));
                return GuardDecision.blocked(
                        "[安全拦截] " + evaluation.summary() + "。请使用更安全的替代方案。");
            }

            if (evaluation.shouldRequireApproval()) {
                List<AssistantMessage.ToolCall> remaining = allToolCalls.subList(currentIndex + 1, allToolCalls.size());
                String approvalResponse = ToolExecutionGuardHelper.handleToolApproval(
                        toolCall, toolName, arguments, evaluation,
                        conversationId, agentId, requesterId, approvalService, streamTracker,
                        events, remaining);
                // Extract pendingId from response (format: "[APPROVAL_PENDING] tool=xxx awaiting user decision")
                return GuardDecision.needsApproval(approvalResponse, extractPendingId(approvalResponse));
            }
        } else if (toolGuard != null) {
            ToolGuardResult guardResult = toolGuard.check(toolName, arguments);

            if (guardResult.isBlocked()) {
                log.warn("[ToolExecutor] Tool call BLOCKED by ToolGuard: tool={}, reason={}", toolName, guardResult.reason());
                events.add(GraphEventPublisher.toolComplete(toolCall.id(), toolName, guardResult.reason(), false));
                return GuardDecision.blocked(
                        "[安全拦截] " + guardResult.reason() + "。请使用更安全的替代方案。");
            }

            if (guardResult.needsApproval()) {
                List<AssistantMessage.ToolCall> remaining = allToolCalls.subList(currentIndex + 1, allToolCalls.size());
                String approvalResponse = ToolExecutionGuardHelper.handleToolApprovalLegacy(
                        toolCall, toolName, arguments, guardResult,
                        conversationId, agentId, requesterId, approvalService, streamTracker,
                        events, remaining);
                return GuardDecision.needsApproval(approvalResponse, extractPendingId(approvalResponse));
            }
        }

        return GuardDecision.allowed();
    }

    // ==================== 辅助方法 ====================

    /**
     * RFC-052: a tool is "direct" when its {@link ToolCallback#getToolMetadata()}
     * reports {@code returnDirect=true}. {@code @Tool(returnDirect=true)} maps
     * here automatically; MCP tools rely on the
     * {@code ReturnDirectMcpToolCallback} decorator to override the metadata
     * (the upstream {@code SyncMcpToolCallback} returns the framework default
     * of {@code false}).
     */
    private static boolean isReturnDirect(ToolCallback callback) {
        try {
            return callback.getToolMetadata() != null
                    && callback.getToolMetadata().returnDirect();
        } catch (Exception e) {
            log.debug("[ToolExecutor] Failed to read returnDirect metadata: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns true when the tool can run in parallel with other safe tools.
     * Consults the registry first (annotation-driven, populated at startup);
     * falls back to the legacy hardcoded set for callers built without a
     * registry (legacy constructor / unit tests).
     */
    private boolean isConcurrencySafe(String toolName) {
        if (concurrencyRegistry != null && concurrencyRegistry.isUnsafe(toolName)) {
            return false;
        }
        return !DEFAULT_UNSAFE_TOOLS.contains(toolName);
    }

    private String classifyBatchPhase(List<PreparedToolCall> preparedCalls) {
        boolean memoryOnly = preparedCalls.stream().allMatch(pc ->
                "read_workspace_memory_file".equals(pc.toolCall.name())
                        || "list_workspace_memory_files".equals(pc.toolCall.name()));
        return memoryOnly ? "reading_memory" : "executing_tool";
    }

    private String normalizeToolExecutionError(Exception e) {
        String message = e != null && e.getMessage() != null ? e.getMessage() : "Unknown error";
        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("conversion from json")
                || lower.contains("unexpected end-of-input")
                || lower.contains("unexpected character escape sequence")
                || lower.contains("json parse error")
                || lower.contains("malformed json")) {
            // Truncated tool_call args — typically from max_tokens being hit while
            // streaming a large `content` field (e.g. renderDocx with 7000+ char
            // markdown body). The fix MUST come from the model: re-emit the same
            // tool call with smaller content per call, OR split the work across
            // multiple sequential tool calls. We tell the LLM directly so the
            // next reasoning iteration knows what to do — without this, models
            // tend to fall back to narrating the result as final_answer text.
            return "Tool execution failed: your tool_call arguments JSON was truncated mid-stream "
                    + "(very likely you hit max_tokens while emitting a long content field). "
                    + "Action required: re-call the SAME tool now in your next response, but "
                    + "(1) make the content field shorter, OR (2) split the work into multiple "
                    + "sequential tool calls (e.g. write the doc in 2-3 chunks via separate calls). "
                    + "Do NOT describe the result as text — you must call the tool again to actually "
                    + "produce the output.";
        }

        if (lower.contains("access denied") && lower.contains("path outside allowed directories")) {
            // 提取目标路径和允许路径
            return "Tool execution failed: target path is outside the allowed workspace directory.";
        }

        return "Tool execution failed: " + message;
    }

    /**
     * 从 approval response 中提取 pendingId（best-effort）
     */
    private String extractPendingId(String approvalResponse) {
        // handleToolApproval 内部已经创建了 pending，这里只做标记
        return approvalResponse;
    }

    // ==================== 内部数据类 ====================

    private record PreparedToolCall(
            AssistantMessage.ToolCall toolCall,
            ToolCallback callback,
            String arguments,
            boolean concurrencySafe,
            int resultIndex,
            String conversationId,
            String requesterId,
            String workspaceBasePath
    ) {}

    private record ApprovalBarrier(String pendingId, String toolName) {}

    private static final class GuardDecision {
        final boolean blocked;
        final boolean needsApproval;
        final String response;
        final String pendingId;

        private GuardDecision(boolean blocked, boolean needsApproval, String response, String pendingId) {
            this.blocked = blocked;
            this.needsApproval = needsApproval;
            this.response = response;
            this.pendingId = pendingId;
        }

        static GuardDecision allowed() { return new GuardDecision(false, false, null, null); }
        static GuardDecision blocked(String response) { return new GuardDecision(true, false, response, null); }
        static GuardDecision needsApproval(String response, String pendingId) {
            return new GuardDecision(false, true, response, pendingId);
        }
    }

    /**
     * 工具执行结果
     */
    public record ToolExecutionResult(
            /** 所有工具的响应（按原始顺序） */
            List<ToolResponseMessage.ToolResponse> responses,
            /** 执行过程中的事件 */
            List<GraphEventPublisher.GraphEvent> events,
            /** 是否有待审批的工具 */
            boolean awaitingApproval,
            /** 审批 pending ID（如果 awaitingApproval=true） */
            String pendingId,
            /** 触发审批 barrier 的工具名（如果 awaitingApproval=true） */
            String barrierToolName,
            /**
             * RFC-052: full-text outputs from any returnDirect tools that ran
             * in this batch. Non-empty list ⇒ graph must short-circuit to
             * FinalAnswerNode without re-entering the LLM.
             */
            List<DirectToolOutput> directOutputs
    ) {
        /** Backwards-compatible constructor for callers that don't track direct outputs. */
        public ToolExecutionResult(List<ToolResponseMessage.ToolResponse> responses,
                                    List<GraphEventPublisher.GraphEvent> events,
                                    boolean awaitingApproval,
                                    String pendingId,
                                    String barrierToolName) {
            this(responses, events, awaitingApproval, pendingId, barrierToolName, List.of());
        }

        public boolean hasDirectOutputs() {
            return directOutputs != null && !directOutputs.isEmpty();
        }
    }
}
