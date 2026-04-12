package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.ThinkingLevelHolder;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.graph.state.MateClawStateKeys;

import vip.mate.channel.web.ChatStreamTracker;

import java.util.*;
import java.util.concurrent.CancellationException;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 推理节点（ReAct Thought 阶段）
 * <p>
 * 调用 LLM 进行单次推理，判断是否需要工具调用。
 * 关键：通过 internalToolExecutionEnabled=false 禁用 ChatModel 内部工具循环，
 * 使 StateGraph 完全控制 ReAct 循环。
 * <p>
 * 支持 forced_tool_call 机制：当审批通过后的重放请求到达时，
 * 跳过 LLM 调用，直接发出预批准的工具调用。
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ReasoningNode implements NodeAction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 单次 LLM 调用的默认最大输出 token 数，防止退化输出无限生成 */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private final ChatModel chatModel;
    private final List<ToolCallback> toolCallbacks;
    private final String reasoningEffort;
    private final NodeStreamingChatHelper streamingHelper;
    private final ConversationWindowManager conversationWindowManager;
    private final ChatStreamTracker streamTracker;
    private final int maxOutputTokens;

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker) {
        this(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager,
                streamTracker, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager,
                         ChatStreamTracker streamTracker, int maxOutputTokens) {
        this.chatModel = chatModel;
        this.toolCallbacks = toolSet.callbacks();
        this.reasoningEffort = reasoningEffort;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.streamTracker = streamTracker;
        this.maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
    }

    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort,
                         NodeStreamingChatHelper streamingHelper,
                         ConversationWindowManager conversationWindowManager) {
        this(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet, String reasoningEffort) {
        this(chatModel, toolSet, reasoningEffort, null, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, AgentToolSet toolSet) {
        this(chatModel, toolSet, null, null, null);
    }

    /** @deprecated */
    @Deprecated
    public ReasoningNode(ChatModel chatModel, List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.toolCallbacks = toolCallbacks;
        this.reasoningEffort = null;
        this.streamingHelper = null;
        this.conversationWindowManager = null;
        this.streamTracker = null;
        this.maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        // ======= 取消检查（LLM 调用前，尚未计数） =======
        String conversationId = accessor.conversationId();
        if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
            log.info("[ReasoningNode] Stop requested before LLM call, aborting");
            throw new CancellationException("Stream stopped by user");
        }

        // ======= forced_tool_call 检测：审批通过后的重放（不计入 LLM 调用） =======
        String forcedToolCallJson = accessor.forcedToolCall();
        if (!forcedToolCallJson.isEmpty()) {
            try {
                log.info("[ReasoningNode] Detected forced_tool_call, skipping LLM, emitting tool call directly");

                AssistantMessage.ToolCall toolCall = deserializeToolCall(forcedToolCallJson);

                AssistantMessage syntheticMsg = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();

                return MateClawStateAccessor.output()
                        .needsToolCall(true)
                        .toolCalls(List.of(toolCall))
                        .messages(List.of((Message) syntheticMsg))
                        .iterationCount(accessor.iterationCount() + 1)
                        .forcedToolCall("")
                        .currentPhase("forced_replay")
                        .contentStreamed(true)
                        .thinkingStreamed(true)
                        .events(List.of(GraphEventPublisher.phase("forced_replay", Map.of(
                                "toolName", toolCall.name(),
                                "iteration", accessor.iterationCount() + 1))))
                        .build();
            } catch (Exception e) {
                log.error("[ReasoningNode] Failed to deserialize forced_tool_call, falling through to normal LLM: {}",
                        e.getMessage());
            }
        }

        // ======= 构建 Prompt =======
        String systemPrompt = accessor.systemPrompt();
        List<Message> messages = accessor.messages();

        // 消息列表膨胀防护
        final int MAX_LOOP_MESSAGES = 40;
        if (messages.size() > MAX_LOOP_MESSAGES) {
            log.warn("[ReasoningNode] Messages list too large ({} messages), trimming to {} for conversation {}",
                    messages.size(), MAX_LOOP_MESSAGES, conversationId);
            List<Message> trimmed = new ArrayList<>(MAX_LOOP_MESSAGES);
            trimmed.addAll(messages.subList(0, Math.min(4, messages.size())));
            trimmed.addAll(messages.subList(messages.size() - (MAX_LOOP_MESSAGES - 4), messages.size()));
            messages = trimmed;
        }

        String workspaceBasePath = state.value(vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH, "");
        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(RuntimeContextInjector.buildContextMessage(workspaceBasePath)));
        promptMessages.addAll(messages);

        // 请求级思考深度覆盖（ThinkingLevelHolder 由 AgentService 设置）
        String effectiveReasoning = resolveEffectiveReasoningEffort();
        log.info("[ReasoningNode] thinkingLevel={}, effectiveReasoningEffort={}, nodeDefault={}",
                ThinkingLevelHolder.get(), effectiveReasoning, this.reasoningEffort);

        ChatOptions options;
        if (StringUtils.hasText(effectiveReasoning)) {
            OpenAiChatOptions oaiOpts = OpenAiChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .reasoningEffort(effectiveReasoning)
                    .maxTokens(maxOutputTokens)
                    .build();
            oaiOpts.setInternalToolExecutionEnabled(false);
            options = oaiOpts;
        } else {
            options = ToolCallingChatOptions.builder()
                    .toolCallbacks(toolCallbacks)
                    .internalToolExecutionEnabled(false)
                    .maxTokens(maxOutputTokens)
                    .build();
        }

        Prompt prompt = new Prompt(promptMessages, options);

        // ======= LLM 调用区域 =======
        // nextLlmCallCount 在首次 streamCall 之前计算。
        // 所有退出路径（正常、stopped、fatal error、CancellationException）都必须写回此值。
        // PTL compact retry 会再 +1。
        int nextLlmCallCount = accessor.llmCallCount() + 1;
        log.debug("[ReasoningNode] Calling LLM with {} messages, {} tool definitions, iteration {}/{}, llmCallCount={}",
                promptMessages.size(), toolCallbacks.size(),
                accessor.iterationCount(), accessor.maxIterations(), nextLlmCallCount);

        GraphEventPublisher.GraphEvent phaseEvent = GraphEventPublisher.phase("reasoning",
                Map.of("iteration", accessor.iterationCount()));
        pushPhase(conversationId, "reasoning", Map.of(
                "iteration", accessor.iterationCount(),
                "llmCallCount", nextLlmCallCount
        ));

        NodeStreamingChatHelper.StreamResult result;
        try {
            result = streamingHelper.streamCall(chatModel, prompt, conversationId, "reasoning");

            // PTL 处理：压缩后重试
            if (result.isPromptTooLong() && conversationWindowManager != null) {
                log.warn("[ReasoningNode] Prompt too long, attempting compaction and retry");
                List<Message> compactedMessages = conversationWindowManager.compactForRetry(messages);
                if (compactedMessages != null && compactedMessages.size() < messages.size()) {
                    List<Message> retryPromptMessages = new ArrayList<>();
                    retryPromptMessages.add(new SystemMessage(systemPrompt));
                    retryPromptMessages.add(new UserMessage(RuntimeContextInjector.buildContextMessage(workspaceBasePath)));
                    retryPromptMessages.addAll(compactedMessages);
                    Prompt retryPrompt = new Prompt(retryPromptMessages, options);
                    log.info("[ReasoningNode] Retrying with compacted messages: {} -> {} messages",
                            messages.size(), compactedMessages.size());
                    // compact retry 是第 2 次 LLM 调用，先递增再调用
                    nextLlmCallCount++;
                    pushPhase(conversationId, "reasoning", Map.of(
                            "iteration", accessor.iterationCount(),
                            "llmCallCount", nextLlmCallCount,
                            "compacted", true
                    ));
                    result = streamingHelper.streamCall(chatModel, retryPrompt, conversationId, "reasoning_compact_retry");
                } else {
                    log.warn("[ReasoningNode] Compaction did not reduce messages, cannot retry");
                }
            }
        } catch (CancellationException ce) {
            // "调用已发出但尚未产出内容时用户停止" — streamHelper 抛 CancellationException。
            // 返回空 finalAnswer + STOPPED，让 FinalAnswerNode 按 STOPPED 语义处理。
            // 必须显式清零 needsToolCall/shouldSummarize，防止前一轮残留标志导致误路由。
            log.info("[ReasoningNode] CancellationException during LLM call (user stopped before first token), " +
                    "returning empty answer with STOPPED, llmCallCount={}", nextLlmCallCount);
            return MateClawStateAccessor.output()
                    .finalAnswer("")
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.STOPPED)
                    .contentStreamed(true)
                    .thinkingStreamed(true)
                    .build();
        }

        // ======= 处理 StreamResult =======

        // 用户主动停止且有部分内容
        if (result.stopped() && result.hasAnyContent()) {
            String partialText = result.text() != null ? result.text() : "";
            String partialThinking = result.thinking() != null ? result.thinking() : "";
            log.info("[ReasoningNode] Stop with partial content ({} chars, thinking {} chars), flushing as final answer",
                    partialText.length(), partialThinking.length());
            var builder = MateClawStateAccessor.output()
                    .finalAnswer(partialText)
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .contentStreamed(true)
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .finishReason(FinishReason.STOPPED);
            if (!partialThinking.isEmpty()) {
                builder.finalThinking(partialThinking);
                builder.thinkingStreamed(true);
            }
            return builder.build();
        }

        // Fatal error：直接设置 finalAnswer 为错误文案 + ERROR_FALLBACK，
        // 不走 LimitExceededNode（后者会再发一次 LLM 调用，语义不对且对认证/配额错误会再失败）。
        // ReasoningDispatcher 看到 !needsToolCall && !shouldSummarize → finalAnswerNode，
        // FinalAnswerNode 检测到 existingAnswer 非空时直接使用，finishReason 保持 ERROR_FALLBACK。
        if (result.hasFatalError()) {
            log.error("[ReasoningNode] Fatal LLM error: {}", result.errorMessage());
            return MateClawStateAccessor.output()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer("[错误] " + result.errorMessage())
                    .llmCallCount(nextLlmCallCount)
                    .finishReason(FinishReason.ERROR_FALLBACK)
                    .contentStreamed(true)
                    .thinkingStreamed(true)
                    .mergeUsage(state, result)
                    .build();
        }

        if (result.partial()) {
            log.warn("[ReasoningNode] Partial LLM result ({} chars), treating as final answer", result.text().length());
        }

        if (result.hasToolCalls()) {
            log.info("[ReasoningNode] LLM requested {} tool call(s): {}",
                    result.toolCalls().size(),
                    result.toolCalls().stream().map(AssistantMessage.ToolCall::name).toList());
            pushPhase(conversationId, "executing_tool", Map.of(
                    "iteration", accessor.iterationCount(),
                    "toolCount", result.toolCalls().size()
            ));

            return MateClawStateAccessor.output()
                    .needsToolCall(true)
                    .shouldSummarize(false)
                    .toolCalls(result.toolCalls())
                    .messages(List.of((Message) result.assistantMessage()))
                    .currentPhase("reasoning")
                    .currentThinking(result.thinking())
                    .streamedContent(result.text() != null ? result.text() : "")
                    .streamedThinking(result.thinking())
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .events(List.of(phaseEvent))
                    .build();
        } else {
            String content = result.text();
            log.info("[ReasoningNode] LLM produced final answer ({} chars)", content != null ? content.length() : 0);
            pushPhase(conversationId, "drafting_answer", Map.of(
                    "iteration", accessor.iterationCount(),
                    "answerChars", content != null ? content.length() : 0
            ));

            return MateClawStateAccessor.output()
                    .needsToolCall(false)
                    .shouldSummarize(false)
                    .finalAnswer(content != null ? content : "")
                    .finalThinking(result.thinking())
                    .messages(List.of((Message) result.assistantMessage()))
                    .currentPhase("reasoning")
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .llmCallCount(nextLlmCallCount)
                    .mergeUsage(state, result)
                    .events(List.of(phaseEvent))
                    .build();
        }
    }

    private AssistantMessage.ToolCall deserializeToolCall(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = OBJECT_MAPPER.readValue(json, Map.class);
            return new AssistantMessage.ToolCall(
                    map.getOrDefault("id", UUID.randomUUID().toString()),
                    map.getOrDefault("type", "function"),
                    map.getOrDefault("name", ""),
                    map.getOrDefault("arguments", "")
            );
        } catch (Exception e) {
            log.error("[ReasoningNode] Failed to deserialize forced_tool_call: {}", e.getMessage());
            throw new RuntimeException("无法反序列化 forced_tool_call: " + e.getMessage(), e);
        }
    }

    private void pushPhase(String conversationId, String phase, Map<String, Object> extra) {
        if (streamTracker == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        streamTracker.updatePhase(conversationId, phase);
        streamTracker.broadcastObject(conversationId, "phase", GraphEventPublisher.phase(phase, extra).data());
    }

    /**
     * 解析有效的 reasoningEffort。
     * 优先级：ThinkingLevelHolder（请求级） > 构造时的 reasoningEffort（Agent/模型默认）。
     * "off" 会清除 reasoningEffort（返回 null）。
     */
    private String resolveEffectiveReasoningEffort() {
        String requestLevel = ThinkingLevelHolder.get();
        if (requestLevel != null) {
            if ("off".equalsIgnoreCase(requestLevel)) {
                return null;
            }
            // thinkingLevel → reasoningEffort 映射
            return switch (requestLevel.toLowerCase()) {
                case "low" -> "low";
                case "medium" -> "medium";
                case "high" -> "high";
                case "max" -> "high"; // OpenAI 最高支持 high
                default -> requestLevel; // 透传未知值
            };
        }
        // 无请求级覆盖，使用构造时的默认值
        return this.reasoningEffort;
    }
}
