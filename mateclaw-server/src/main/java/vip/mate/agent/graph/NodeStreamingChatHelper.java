package vip.mate.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import vip.mate.channel.web.ChatStreamTracker;

import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 节点级流式 LLM 调用辅助
 * <p>
 * 核心原则：模型流驱动渠道流，State 只保存最终聚合结果。
 * <ul>
 *   <li>调用 {@code chatModel.stream(prompt)}，逐 chunk 处理</li>
 *   <li>从每个 chunk 中提取 content delta 和 thinking delta（reasoningContent）</li>
 *   <li>通过 {@link ChatStreamTracker} 实时广播 content_delta / thinking_delta</li>
 *   <li>同时内部累积完整 text、thinking 和 tool calls</li>
 *   <li>流结束后返回 {@link StreamResult} 供节点写回 State</li>
 * </ul>
 * <p>
 * 所有面向用户的 LLM 节点（ReasoningNode、StepExecutionNode、PlanSummaryNode 等）
 * 统一使用此 helper，而不是各自散落 {@code chatModel.call()}。
 *
 * @author MateClaw Team
 */
@Slf4j
public class NodeStreamingChatHelper {

    private final ChatStreamTracker streamTracker;

    /**
     * Ordered fallback chain tried after the primary model exhausts retries.
     * Each entry is attempted once (no retry); the first successful response
     * wins. Empty list disables fallover entirely.
     *
     * <p>Stored as {@link vip.mate.llm.failover.FallbackEntry} (providerId +
     * ChatModel) so the chain walker can consult {@link vip.mate.llm.failover.ProviderHealthTracker}
     * — cooldown state is keyed by providerId, not by ChatModel instance.</p>
     */
    private final List<vip.mate.llm.failover.FallbackEntry> fallbackChain;

    /** Optional cache-metrics aggregator; {@code null} in tests or when the bean is absent. */
    private final vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics;

    /** Optional per-provider health tracker; {@code null} in tests or when bean absent. */
    private final vip.mate.llm.failover.ProviderHealthTracker healthTracker;

    /**
     * Provider id of the primary {@link ChatModel} this helper drives. Used
     * by {@link #streamCallInternal} to consult / update {@link #healthTracker}
     * for the primary too — if a provider's API key is revoked, primary
     * cooldown lets us bypass the 5-retry stall on subsequent calls within
     * the same conversation. Falls back to {@code null} when unknown
     * (legacy callers, tests).
     */
    private final String primaryProviderId;

    public NodeStreamingChatHelper(ChatStreamTracker streamTracker) {
        this(streamTracker, List.of(), null, null, null);
    }

    /**
     * @deprecated use the full constructor — a single fallback cannot
     *     express the ordered multi-provider chain
     */
    @Deprecated
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker, ChatModel fallbackModel) {
        this(streamTracker, wrap(fallbackModel), null, null, null);
    }

    /**
     * @deprecated use the full constructor.
     */
    @Deprecated
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker, ChatModel fallbackModel,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics) {
        this(streamTracker, wrap(fallbackModel), cacheMetrics, null, null);
    }

    /**
     * Chain constructor without health tracker — primarily for tests and
     * legacy wiring. Production callers should use the full constructor.
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics) {
        this(streamTracker, fallbackChain, cacheMetrics, null, null);
    }

    /**
     * Constructor with health tracker but unknown primary provider — used by
     * tests where the helper isn't tied to a specific primary. Primary
     * health tracking is disabled for instances built this way.
     */
    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics,
                                   vip.mate.llm.failover.ProviderHealthTracker healthTracker) {
        this(streamTracker, fallbackChain, cacheMetrics, healthTracker, null);
    }

    public NodeStreamingChatHelper(ChatStreamTracker streamTracker,
                                   List<vip.mate.llm.failover.FallbackEntry> fallbackChain,
                                   vip.mate.llm.cache.LlmCacheMetricsAggregator cacheMetrics,
                                   vip.mate.llm.failover.ProviderHealthTracker healthTracker,
                                   String primaryProviderId) {
        this.streamTracker = streamTracker;
        this.fallbackChain = fallbackChain == null ? List.of() : List.copyOf(fallbackChain);
        this.cacheMetrics = cacheMetrics;
        this.healthTracker = healthTracker;
        this.primaryProviderId = primaryProviderId;
    }

    private static List<vip.mate.llm.failover.FallbackEntry> wrap(ChatModel m) {
        // Legacy single-fallback path: providerId is unknown so health tracking
        // is silently disabled for that one entry (it gets a synthetic id).
        return m == null ? List.of() : List.of(new vip.mate.llm.failover.FallbackEntry("__legacy__", m));
    }

    /**
     * Record a single primary-model outcome to the health tracker. No-op when
     * either the tracker bean isn't wired or the primary's providerId is
     * unknown (e.g., tests, legacy callers built without the full constructor).
     */
    private void recordPrimary(boolean success) {
        if (healthTracker == null || primaryProviderId == null) return;
        if (success) healthTracker.recordSuccess(primaryProviderId);
        else healthTracker.recordFailure(primaryProviderId);
    }

    /**
     * 流式调用 LLM 并实时广播增量内容
     *
     * @param chatModel      LLM 模型
     * @param prompt         完整 prompt
     * @param conversationId 会话 ID，用于广播
     * @param phase          阶段标识，用于日志（如 "reasoning"、"step_execution"）
     * @return 聚合结果
     */
    public StreamResult streamCall(ChatModel chatModel, Prompt prompt,
                                   String conversationId, String phase) {
        return streamCallInternal(chatModel, prompt, conversationId, phase, true);
    }

    /**
     * 流式调用 LLM 但不广播增量内容到前端。
     * <p>
     * 用于 PlanGenerationNode 等返回结构化 JSON 的节点 —— LLM 输出不应直接展示给用户，
     * 需要后续解析后再决定是否广播。
     *
     * @param chatModel      LLM 模型
     * @param prompt         完整 prompt
     * @param conversationId 会话 ID（仅用于日志，不广播）
     * @param phase          阶段标识
     * @return 聚合结果
     */
    public StreamResult streamCallSilent(ChatModel chatModel, Prompt prompt,
                                          String conversationId, String phase) {
        return streamCallInternal(chatModel, prompt, conversationId, phase, false);
    }

    /**
     * 广播文本内容到前端（用于 silent 调用后手动推送 direct_answer 等）
     */
    public void broadcastContent(String conversationId, String content) {
        if (content != null && !content.isEmpty()) {
            broadcastDelta(conversationId, "content_delta", content);
        }
    }

    // ==================== 重试配置 ====================

    private static final int MAX_RETRIES = 5;
    private static final long BACKOFF_BASE_MS = 3000;
    private static final long BACKOFF_CAP_MS = 60_000;

    /**
     * 判断错误是否可重试（基于状态码/异常类型）
     */
    private static boolean isRetryable(Throwable error) {
        String msg = extractFullErrorChain(error);
        // Kimi engine_overloaded / 标准 HTTP 错误 / 速率限制
        return msg.contains("engine_overloaded")
                || msg.contains("rate_limit") || msg.contains("RateLimitError")
                || msg.contains("429") || msg.contains("Too Many Requests")
                || msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("APITimeoutError") || msg.contains("APIConnectionError")
                || msg.contains("Connection reset") || msg.contains("Connection refused");
    }

    /**
     * 分类错误类型（用于分级重试和上层 Node 决策）
     */
    private static ErrorType classifyError(Throwable error) {
        String msg = extractFullErrorChain(error);
        // PTL: prompt too long / context length exceeded
        if (msg.contains("prompt is too long")
                || msg.contains("context_length_exceeded")
                || msg.contains("context length exceeded")
                || msg.contains("maximum context length")
                || msg.contains("token limit")
                || msg.contains("This model's maximum context length")
                || msg.contains("请求体中的 input tokens 总数超出了模型允许")) {
            return ErrorType.PROMPT_TOO_LONG;
        }
        // Auth errors
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("Invalid API Key")
                || msg.contains("authentication") || msg.contains("AuthenticationError")) {
            return ErrorType.AUTH_ERROR;
        }
        // Rate limit
        if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("RateLimitError")
                || msg.contains("Too Many Requests") || msg.contains("engine_overloaded")) {
            return ErrorType.RATE_LIMIT;
        }
        // Thinking block errors (Anthropic: old thinking blocks cannot be modified)
        if (msg.contains("thinking blocks cannot be modified")
                || msg.contains("thinking content is not allowed")
                || msg.contains("thinking block")) {
            return ErrorType.THINKING_BLOCK_ERROR;
        }
        // BILLING — payment / quota exhausted. Distinct from AUTH because
        // a different provider may have credits, so we should fall back instead of
        // terminating the call. Both OpenAI ("insufficient_quota") and Anthropic
        // ("credit balance is too low") use these phrases in 402-class responses.
        if (msg.contains("402") || msg.contains("insufficient_quota")
                || msg.contains("credit balance is too low")
                || msg.contains("billing_error") || msg.contains("billing_hard_limit_reached")
                || msg.contains("You exceeded your current quota")
                || msg.contains("quota exceeded") || msg.contains("Quota exceeded")) {
            return ErrorType.BILLING;
        }
        // MODEL_NOT_FOUND — provider rejects the requested model id.
        // Includes DashScope's "[InvalidParameter] url error, please check url"
        // (https://help.aliyun.com/zh/model-studio/error-code#error-url) which despite
        // the wording is the provider rejecting an unknown/unsupported model id on
        // the native protocol. Splitting this out from CLIENT_ERROR lets us hand off
        // to the fallback chain instead of terminating — a different provider may
        // recognize the model name (or have an equivalent default).
        if (msg.contains("Model not exist")
                || msg.contains("model_not_found")
                || msg.contains("Model not found")
                || msg.contains("does not exist")
                || msg.contains("[InvalidParameter]")
                || msg.contains("InvalidParameter")
                || msg.contains("url error")) {
            return ErrorType.MODEL_NOT_FOUND;
        }
        // Client errors (400 Bad Request — unsupported format, invalid params, etc.) — NOT retryable
        if (msg.contains("400") || msg.contains("Bad Request")
                || msg.contains("invalid_request_error") || msg.contains("unsupported")) {
            return ErrorType.CLIENT_ERROR;
        }
        // Server errors
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("APITimeoutError") || msg.contains("APIConnectionError")
                || msg.contains("Connection reset") || msg.contains("Connection refused")
                || msg.contains("timeout") || msg.contains("Timeout")) {
            return ErrorType.SERVER_ERROR;
        }
        return ErrorType.UNKNOWN;
    }

    /** 提取完整异常链信息用于关键字匹配 */
    private static String extractFullErrorChain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = error;
        while (cur != null) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append(" | ");
            }
            sb.append(cur.getClass().getSimpleName()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    private StreamResult streamCallInternal(ChatModel chatModel, Prompt prompt,
                                             String conversationId, String phase,
                                             boolean broadcast) {
        // 在开始 LLM 调用前检查停止标志
        if (streamTracker.isStopRequested(conversationId)) {
            log.info("[{}] Stop requested before LLM call, aborting: conversationId={}", phase, conversationId);
            throw new CancellationException("Stream stopped by user");
        }

        // if the primary's provider is in cooldown (3+ recent
        // consecutive failures within the cooldown window), skip the 5-retry
        // primary loop entirely and head straight to the fallback chain.
        // Without this short-circuit a degraded primary forces every LLM
        // call in the conversation to wait through the full backoff.
        boolean primarySkipped = primaryProviderId != null
                && healthTracker != null
                && healthTracker.isInCooldown(primaryProviderId);
        if (primarySkipped) {
            log.warn("[{}] Primary provider={} is in cooldown — skipping straight to fallback chain",
                    phase, primaryProviderId);
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("主模型暂时不可用（冷却中），直接尝试备选模型..."));
            }
        }

        // 主模型重试循环
        StreamResult lastResult = null;
        if (!primarySkipped) for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            lastResult = doStreamCall(chatModel, prompt, conversationId, phase, broadcast, attempt);
            if (lastResult != null) {
                // PTL: 不重试，直接返回给上层 Node 处理
                if (lastResult.errorType() == ErrorType.PROMPT_TOO_LONG) {
                    return lastResult;
                }
                // AUTH: primary key 失效不会自愈，跳过同模型重试，交给 fallback chain
                // — 其它 provider 的 key 可能仍然可用（与 BILLING / MODEL_NOT_FOUND 同策略）。
                // recordPrimary(false) 仍记一次失败用于 healthTracker 冷却累计。
                // 若 fallback chain 全部 401，walker 末尾会把最后一次 AUTH_ERROR 透出，
                // 不会静默吞错。
                if (lastResult.errorType() == ErrorType.AUTH_ERROR) {
                    log.warn("[{}] Primary auth failed — skipping same-model retries, handing off to fallback chain", phase);
                    recordPrimary(false);
                    break;
                }
                // BILLING / MODEL_NOT_FOUND — provider-side hard failures
                // that won't change on retry. Skip to fallback chain (a different
                // provider may have credits, or the model name may be valid there).
                if (lastResult.errorType() == ErrorType.BILLING
                        || lastResult.errorType() == ErrorType.MODEL_NOT_FOUND) {
                    log.warn("[{}] Primary error={} — skipping same-model retries, handing off to fallback chain",
                            phase, lastResult.errorType());
                    recordPrimary(false);
                    break;
                }
                // CLIENT_ERROR (400 Bad Request): 不重试（参数/格式错误重试也不会变）
                if (lastResult.errorType() == ErrorType.CLIENT_ERROR) {
                    return lastResult;
                }
                // THINKING_BLOCK_ERROR: 剥离旧 thinking 块后单次重试
                if (lastResult.errorType() == ErrorType.THINKING_BLOCK_ERROR && attempt == 0) {
                    log.warn("[{}] Thinking block error detected, stripping old thinking and retrying once", phase);
                    prompt = stripThinkingFromPrompt(prompt);
                    continue; // 重试一次
                }
                if (lastResult.errorType() == ErrorType.THINKING_BLOCK_ERROR) {
                    return lastResult; // 已经重试过了
                }
                // EMPTY_RESPONSE — break the primary-retry loop and fall through to
                // the fallback chain. Retrying the same model that returned nothing is rarely
                // productive; a different provider has a better chance of succeeding.
                if (lastResult.errorType() == ErrorType.EMPTY_RESPONSE) {
                    log.warn("[{}] Primary returned empty response — skipping same-model retries, handing off to fallback chain", phase);
                    recordPrimary(false);
                    break;
                }
                // 成功
                if (lastResult.errorMessage() == null || lastResult.errorType() == ErrorType.NONE) {
                    recordPrimary(true);
                    return lastResult;
                }
                // Any other non-null errored result with a classified type that doStreamCall
                // chose NOT to retry (i.e. UNKNOWN, or RATE_LIMIT/SERVER_ERROR past MAX_RETRIES)
                // must exit — otherwise we silently spin through attempts and waste seconds
                // per turn on unrecoverable errors like DashScope's "url error" / unknown model.
                recordPrimary(false);
                return lastResult;
            }
            // lastResult == null 表示需要重试
        }
        // If we exhausted the retry loop without a verdict, primary effectively failed.
        if (!primarySkipped && lastResult != null && lastResult.errorType() != ErrorType.NONE) {
            recordPrimary(false);
        }

        // Primary exhausted retries — walk the fallback chain in priority order.
        // Each fallback gets a single shot (no retry); first successful result wins.
        // Same-instance entries (e.g., primary accidentally included in the chain)
        // are skipped so we don't re-try the exact model that just failed.
        // Providers in cooldown  are also skipped so a known-bad
        // provider doesn't add latency to every conversation turn.
        for (int i = 0; i < fallbackChain.size(); i++) {
            vip.mate.llm.failover.FallbackEntry entry = fallbackChain.get(i);
            ChatModel fallback = entry.chatModel();
            if (fallback == chatModel) continue;
            if (healthTracker != null && healthTracker.isInCooldown(entry.providerId())) {
                log.info("[{}] Skipping fallback {}/{} provider={} — in cooldown",
                        phase, i + 1, fallbackChain.size(), entry.providerId());
                continue;
            }
            log.warn("[{}] Primary exhausted, trying fallback {}/{} provider={} ({}) for conversation {}",
                    phase, i + 1, fallbackChain.size(), entry.providerId(),
                    fallback.getClass().getSimpleName(), conversationId);
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("主模型不可用，正在切换到备选模型 (" + (i + 1) + "/" + fallbackChain.size() + ")..."));
            }
            StreamResult fallbackResult = doStreamCall(fallback, prompt, conversationId,
                    phase + "_fallback_" + (i + 1), broadcast, 0);
            // Accept only fully successful fallbacks. Non-successful results (auth
            // error, client error, still-rate-limited) propagate to the next
            // fallback instead of being surfaced as the final result.
            if (fallbackResult != null
                    && fallbackResult.errorType() == ErrorType.NONE
                    && fallbackResult.errorMessage() == null) {
                if (healthTracker != null) healthTracker.recordSuccess(entry.providerId());
                return fallbackResult;
            }
            if (healthTracker != null) healthTracker.recordFailure(entry.providerId());
            if (fallbackResult != null) {
                lastResult = fallbackResult; // remember most recent to report if the whole chain fails
            }
        }

        return lastResult != null ? lastResult
                : buildErrorResult("LLM 调用失败，已达最大重试次数", conversationId, phase);
    }

    /**
     * 单次流式调用尝试。
     * @return StreamResult 如果成功/降级/不可重试；null 如果应该重试
     */
    private StreamResult doStreamCall(ChatModel chatModel, Prompt prompt,
                                       String conversationId, String phase,
                                       boolean broadcast, int attempt) {
        if (attempt > 0) {
            long delay = Math.min(BACKOFF_BASE_MS * (1L << (attempt - 1)), BACKOFF_CAP_MS);
            // 加入 jitter 防止雷群效应（a comparable reference runtime 风格）
            delay += ThreadLocalRandom.current().nextLong(0, Math.max(1, delay / 2));
            delay = Math.min(delay, BACKOFF_CAP_MS);
            log.warn("[{}] Retry attempt {}/{} after {}ms for conversation {}",
                    phase, attempt, MAX_RETRIES, delay, conversationId);
            // 广播给前端：用户可见的重试倒计时
            if (broadcast) {
                broadcastDelta(conversationId, "warning",
                        buildDeltaJson("⏱️ 请求频率受限，等待 " + (delay / 1000) + " 秒后重试（第 " + attempt + "/" + MAX_RETRIES + " 次）..."));
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return buildErrorResult("LLM 调用被中断", conversationId, phase);
            }
        }

        StringBuilder contentAccum = new StringBuilder();
        StringBuilder thinkingAccum = new StringBuilder();
        List<ToolCallAccumulator> toolCallAccumulators = new ArrayList<>();
        AtomicReference<AssistantMessage> lastAssistantMessage = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicInteger promptTokens = new AtomicInteger(0);
        AtomicInteger completionTokens = new AtomicInteger(0);
        // RFC-014: Anthropic prompt cache 计数（其它 provider 永远为 0）
        AtomicInteger cacheReadTokens = new AtomicInteger(0);
        AtomicInteger cacheWriteTokens = new AtomicInteger(0);

        // 重复检测器：检测 LLM 退化输出（如不断重复同一句话）
        RepetitionDetector contentRepDetector = new RepetitionDetector();
        RepetitionDetector thinkingRepDetector = new RepetitionDetector();
        // 重复检测触发后设为 true，外层轮询线程据此 dispose 订阅
        AtomicBoolean repetitionTriggered = new AtomicBoolean(false);

        CountDownLatch latch = new CountDownLatch(1);

        Disposable subscription = chatModel.stream(prompt)
                .doOnNext(chatResponse -> {
                    if (chatResponse == null || chatResponse.getResults() == null || chatResponse.getResults().isEmpty()) {
                        return;
                    }
                    var generation = chatResponse.getResult();
                    AssistantMessage msg = generation.getOutput();
                    lastAssistantMessage.set(msg);

                    // 重复已触发 → 跳过一切处理（等外层 dispose）
                    if (repetitionTriggered.get()) {
                        return;
                    }

                    // 1. 提取 content delta（含重复检测）
                    String contentDelta = msg.getText();
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        if (contentRepDetector.appendAndCheck(contentDelta)) {
                            log.warn("[{}] Content repetition detected, will cancel stream " +
                                    "for conversation {}", phase, conversationId);
                            repetitionTriggered.set(true);
                            return;
                        }
                        contentAccum.append(contentDelta);
                        if (broadcast) {
                            broadcastDelta(conversationId, "content_delta", contentDelta);
                        }
                    }

                    // 2. 提取 thinking delta（含重复检测）
                    String thinkingDelta = extractReasoningContent(msg);
                    if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
                        if (thinkingRepDetector.appendAndCheck(thinkingDelta)) {
                            log.warn("[{}] Thinking repetition detected, will cancel stream " +
                                    "for conversation {}", phase, conversationId);
                            repetitionTriggered.set(true);
                            return;
                        }
                        thinkingAccum.append(thinkingDelta);
                        // thinkingLevel=off 时不广播 thinking（模型仍可能产生，但前端不展示）
                        boolean suppressThinking = "off".equalsIgnoreCase(
                                vip.mate.agent.ThinkingLevelHolder.get());
                        if (broadcast && !suppressThinking) {
                            broadcastDelta(conversationId, "thinking_delta", thinkingDelta);
                        }
                    }

                    // 3. 累积 tool calls（处理分片）
                    if (msg.hasToolCalls()) {
                        accumulateToolCalls(msg.getToolCalls(), toolCallAccumulators);
                    }

                    // 4. 提取 token usage（通常最后一个 chunk 携带完整 usage）
                    if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                        var usage = chatResponse.getMetadata().getUsage();
                        if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                            promptTokens.set(usage.getPromptTokens().intValue());
                        }
                        if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) {
                            completionTokens.set(usage.getCompletionTokens().intValue());
                        }
                        // RFC-014: 反射抽取 Anthropic prompt cache 字段（DashScope/OpenAI 自然返回 0）
                        var cache = vip.mate.llm.cache.CacheUsageExtractor.extract(usage);
                        if (cache.cacheReadTokens() > 0)  cacheReadTokens.set(cache.cacheReadTokens());
                        if (cache.cacheWriteTokens() > 0) cacheWriteTokens.set(cache.cacheWriteTokens());
                    }
                })
                .subscribe(
                        chunk -> { /* 处理逻辑已在 doOnNext 中完成 */ },
                        err -> { errorRef.set(err); latch.countDown(); },
                        latch::countDown
                );

        // 阻塞等待流完成（节点本身是同步 NodeAction），每 500ms 检查一次停止/重复标志
        try {
            long deadlineMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
            while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                // 重复检测触发 → 立即 dispose 上游订阅，停止消耗 tokens
                if (repetitionTriggered.get()) {
                    log.warn("[{}] Repetition detected, disposing upstream subscription " +
                            "for conversation {}", phase, conversationId);
                    subscription.dispose();
                    if (broadcast) {
                        broadcastDelta(conversationId, "warning",
                                buildDeltaJson("检测到模型输出重复，已自动截断"));
                    }
                    // dispose 后 latch 可能不会 countDown，直接跳出
                    break;
                }
                if (streamTracker.isStopRequested(conversationId)) {
                    // 用户主动停止 — 也 dispose 上游
                    subscription.dispose();
                    boolean hasContent = !contentAccum.isEmpty() || !thinkingAccum.isEmpty()
                            || !toolCallAccumulators.isEmpty();
                    if (hasContent) {
                        log.info("[{}] Stop requested during LLM call with partial content " +
                                        "(content={} chars, thinking={} chars, toolCalls={}), " +
                                        "returning stopped partial result: conversationId={}",
                                phase, contentAccum.length(), thinkingAccum.length(),
                                toolCallAccumulators.size(), conversationId);
                        return assembleStoppedResult(contentAccum, thinkingAccum, toolCallAccumulators,
                                promptTokens.get(), completionTokens.get(),
                                cacheReadTokens.get(), cacheWriteTokens.get(), phase);
                    }
                    log.info("[{}] Stop requested during LLM call, no content accumulated, aborting: conversationId={}",
                            phase, conversationId);
                    throw new CancellationException("Stream stopped by user");
                }
                if (System.currentTimeMillis() > deadlineMs) {
                    subscription.dispose();
                    log.warn("[{}] Stream call timed out for conversation {}", phase, conversationId);
                    return buildErrorResult("LLM 调用超时", conversationId, phase);
                }
            }
        } catch (InterruptedException e) {
            subscription.dispose();
            Thread.currentThread().interrupt();
            return buildErrorResult("LLM 调用被中断", conversationId, phase);
        }

        Throwable error = errorRef.get();
        if (error != null) {
            boolean hasAccumulatedContent = !contentAccum.isEmpty() || !toolCallAccumulators.isEmpty();

            if (hasAccumulatedContent) {
                // ===== 优雅降级：LLM 已产出部分内容（如 engine_overloaded 在流尾部触发） =====
                log.warn("[{}] Stream error after partial content ({} chars, {} tool calls), " +
                                "using accumulated content as partial result: {}",
                        phase, contentAccum.length(), toolCallAccumulators.size(), error.getMessage());
                if (broadcast) {
                    broadcastDelta(conversationId, "warning",
                            buildDeltaJson("LLM 响应中断，使用已生成的部分内容继续"));
                }
                return assembleResult(contentAccum, thinkingAccum, toolCallAccumulators,
                        promptTokens.get(), completionTokens.get(),
                        cacheReadTokens.get(), cacheWriteTokens.get(),
                        phase, true, error.getMessage());
            }

            // ===== 无内容：分类错误并决定是否重试 =====
            ErrorType errorType = classifyError(error);

            // PTL: 不重试，返回给上层 Node 处理压缩
            if (errorType == ErrorType.PROMPT_TOO_LONG) {
                log.warn("[{}] Prompt too long error, returning to node for compaction: {}",
                        phase, error.getMessage());
                return buildErrorResultWithType("Prompt 过长: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Auth: 不重试
            if (errorType == ErrorType.AUTH_ERROR) {
                log.error("[{}] Authentication error, not retrying: {}", phase, error.getMessage());
                return buildErrorResultWithType("认证失败: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Client error (400): 不重试（参数/格式错误重试也不会变）
            if (errorType == ErrorType.CLIENT_ERROR) {
                log.error("[{}] Client error (400), not retrying: {}", phase, error.getMessage());
                return buildErrorResultWithType("Bad request: " + extractUserFriendlyError(error),
                        conversationId, phase, errorType);
            }

            // Rate limit / Server error: 重试
            if (attempt < MAX_RETRIES && (errorType == ErrorType.RATE_LIMIT || errorType == ErrorType.SERVER_ERROR)) {
                log.warn("[{}] Retryable error (attempt {}/{}, type={}): {}",
                        phase, attempt, MAX_RETRIES, errorType, error.getMessage());
                return null;  // 返回 null 触发重试
            }

            // 不可重试或已耗尽重试
            log.error("[{}] LLM call failed after {} attempts for conversation {}: {}",
                    phase, attempt + 1, conversationId, error.getMessage());
            return buildErrorResultWithType("LLM 调用失败: " + extractUserFriendlyError(error),
                    conversationId, phase, errorType);
        }

        // ===== 成功（检查是否因重复被截断） =====
        boolean truncatedByRepetition = repetitionTriggered.get();
        if (truncatedByRepetition) {
            log.warn("[{}] LLM output was truncated due to repetition detection for conversation {}",
                    phase, conversationId);
            // warning 已在 dispose 时广播，无需重复
        }

        // guard against silent empty responses. Some providers return
        // HTTP 200 with an empty body under soft-failure conditions (rate-limit
        // capacity, context filter, upstream overload). Treat this as a failure
        // signal so streamCallInternal can hand off to the fallback chain.
        // Only fire when the primary wasn't truncated by our own repetition
        // detector (which deliberately produces short content) and when there
        // are no tool calls (tool-only responses are legitimately empty-text).
        if (!truncatedByRepetition
                && contentAccum.length() == 0
                && thinkingAccum.length() == 0
                && toolCallAccumulators.isEmpty()) {
            log.warn("[{}] LLM returned empty response (no content, no thinking, no tool calls) — marking as EMPTY_RESPONSE for fallback", phase);
            return buildErrorResultWithType("LLM 返回空响应", conversationId, phase, ErrorType.EMPTY_RESPONSE);
        }

        return assembleResult(contentAccum, thinkingAccum, toolCallAccumulators,
                promptTokens.get(), completionTokens.get(),
                cacheReadTokens.get(), cacheWriteTokens.get(), phase,
                truncatedByRepetition, truncatedByRepetition ? "output_truncated_repetition" : null);
    }

    /** 组装 stopped partial 结果（用户主动停止，有已累积内容） */
    private StreamResult assembleStoppedResult(StringBuilder contentAccum, StringBuilder thinkingAccum,
                                                List<ToolCallAccumulator> toolCallAccumulators,
                                                int promptTok, int completionTok,
                                                int cacheReadTok, int cacheWriteTok, String phase) {
        List<AssistantMessage.ToolCall> finalToolCalls = buildFinalToolCalls(toolCallAccumulators);
        String fullContent = contentAccum.toString();
        String fullThinking = thinkingAccum.toString();

        // Fallback: <think> 标签提取
        if (fullThinking.isEmpty() && fullContent.contains("<think>")) {
            var extracted = extractThinkTags(fullContent);
            if (!extracted.thinking.isEmpty()) {
                fullThinking = extracted.thinking;
                fullContent = extracted.content;
            }
        }

        AssistantMessage assembledMessage = !finalToolCalls.isEmpty()
                ? AssistantMessage.builder().content(fullContent).toolCalls(finalToolCalls).build()
                : new AssistantMessage(fullContent);

        recordCacheMetrics(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
        return new StreamResult(fullContent, fullThinking, assembledMessage,
                finalToolCalls, !finalToolCalls.isEmpty(), promptTok, completionTok,
                true, null, ErrorType.NONE, true, cacheReadTok, cacheWriteTok);
    }

    /** 组装最终 StreamResult（成功或 partial） */
    private StreamResult assembleResult(StringBuilder contentAccum, StringBuilder thinkingAccum,
                                         List<ToolCallAccumulator> toolCallAccumulators,
                                         int promptTok, int completionTok,
                                         int cacheReadTok, int cacheWriteTok,
                                         String phase, boolean partial, String errorMsg) {
        List<AssistantMessage.ToolCall> finalToolCalls = buildFinalToolCalls(toolCallAccumulators);
        String fullContent = contentAccum.toString();
        String fullThinking = thinkingAccum.toString();

        // Fallback: <think> 标签提取
        if (fullThinking.isEmpty() && fullContent.contains("<think>")) {
            var extracted = extractThinkTags(fullContent);
            if (!extracted.thinking.isEmpty()) {
                fullThinking = extracted.thinking;
                fullContent = extracted.content;
                log.debug("[{}] Extracted <think> tags from content: {} thinking chars, {} content chars",
                        phase, fullThinking.length(), fullContent.length());
            }
        }

        AssistantMessage assembledMessage;
        if (!finalToolCalls.isEmpty()) {
            assembledMessage = AssistantMessage.builder()
                    .content(fullContent)
                    .toolCalls(finalToolCalls)
                    .build();
        } else {
            assembledMessage = new AssistantMessage(fullContent);
        }

        recordCacheMetrics(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
        return new StreamResult(fullContent, fullThinking, assembledMessage,
                finalToolCalls, !finalToolCalls.isEmpty(), promptTok, completionTok,
                partial, errorMsg, ErrorType.NONE, false, cacheReadTok, cacheWriteTok);
    }

    /**
     * Record token / cache usage to the optional metrics aggregator.
     * Called only from successful assembly paths ({@link #assembleResult}
     * and {@link #assembleStoppedResult}) — error paths are excluded because
     * their token counts are typically zero and would skew the ratio.
     */
    private void recordCacheMetrics(String phase, int promptTok, int completionTok,
                                    int cacheReadTok, int cacheWriteTok) {
        if (cacheMetrics == null) return;
        // Skip empty-usage records (pure error responses or broken chunks).
        if (promptTok == 0 && completionTok == 0 && cacheReadTok == 0 && cacheWriteTok == 0) {
            return;
        }
        cacheMetrics.record(phase, promptTok, completionTok, cacheReadTok, cacheWriteTok);
    }

    /** 构建纯错误 StreamResult（无任何内容） */
    /**
     * 从 Prompt 中剥离旧 AssistantMessage 的 thinking/reasoningContent metadata。
     * 保留最新一条 AssistantMessage 的 thinking（可能是模型需要的签名）。
     */
    private Prompt stripThinkingFromPrompt(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        // 找最后一个 AssistantMessage
        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage) {
                lastAssistantIdx = i;
                break;
            }
        }
        List<Message> cleaned = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am && i != lastAssistantIdx) {
                Map<String, Object> meta = am.getMetadata();
                if (meta != null && meta.containsKey("reasoningContent")) {
                    // 用 builder 重建 AssistantMessage，去掉 reasoningContent
                    Map<String, Object> cleanMeta = new java.util.HashMap<>(meta);
                    cleanMeta.remove("reasoningContent");
                    AssistantMessage.Builder builder = AssistantMessage.builder()
                            .content(am.getText())
                            .properties(cleanMeta);
                    if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                        builder.toolCalls(am.getToolCalls());
                    }
                    if (am.getMedia() != null && !am.getMedia().isEmpty()) {
                        builder.media(am.getMedia());
                    }
                    cleaned.add(builder.build());
                    continue;
                }
            }
            cleaned.add(msg);
        }
        log.info("[ThinkingRecovery] Stripped thinking blocks from {} messages, last assistant at index {}",
                messages.size(), lastAssistantIdx);
        return new Prompt(cleaned, prompt.getOptions());
    }

    private StreamResult buildErrorResult(String errorMsg, String conversationId, String phase) {
        log.error("[{}] Building error result for conversation {}: {}", phase, conversationId, errorMsg);
        if (streamTracker != null && conversationId != null) {
            broadcastDelta(conversationId, "warning",
                    buildDeltaJson(errorMsg));
        }
        AssistantMessage errorMessage = new AssistantMessage("[错误] " + errorMsg);
        return new StreamResult("[错误] " + errorMsg, "", errorMessage,
                List.of(), false, 0, 0, false, errorMsg, ErrorType.UNKNOWN);
    }

    /** 构建带错误类型的 StreamResult */
    private StreamResult buildErrorResultWithType(String errorMsg, String conversationId,
                                                    String phase, ErrorType errorType) {
        log.error("[{}] Building typed error result for conversation {}: {} (type={})",
                phase, conversationId, errorMsg, errorType);
        if (streamTracker != null && conversationId != null) {
            broadcastDelta(conversationId, "warning", buildDeltaJson(errorMsg));
            // 广播结构化 error 事件，供前端展示错误卡片
            String errorJson = buildErrorEventJson(errorMsg, conversationId, errorType);
            streamTracker.broadcast(conversationId, "error", errorJson);
        }
        AssistantMessage errorMessage = new AssistantMessage("[错误] " + errorMsg);
        return new StreamResult("[错误] " + errorMsg, "", errorMessage,
                List.of(), false, 0, 0, false, errorMsg, errorType);
    }

    /** 构建 error 事件的 JSON payload */
    private static String buildErrorEventJson(String message, String conversationId, ErrorType errorType) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"message\":\"");
        appendJsonEscaped(sb, message);
        sb.append("\",\"conversationId\":\"");
        appendJsonEscaped(sb, conversationId);
        sb.append("\",\"errorType\":\"");
        sb.append(errorType.name());
        sb.append("\"}");
        return sb.toString();
    }

    /** JSON 字符串转义辅助 */
    private static void appendJsonEscaped(StringBuilder sb, String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
    }

    /** 从异常链提取用户友好的错误信息 */
    private static String extractUserFriendlyError(Throwable error) {
        String msg = error.getMessage();
        if (msg == null) msg = error.getClass().getSimpleName();

        // 若是 WebClientResponseException，先把 response body 也并入判定样本，
        // 因为 Ollama 的 "does not support tools" 错误只在 body 里，不在 status line 里。
        String bodySample = "";
        Throwable cursor = error;
        for (int i = 0; cursor != null && i < 5; i++, cursor = cursor.getCause()) {
            if (cursor instanceof WebClientResponseException wre) {
                try {
                    String body = wre.getResponseBodyAsString();
                    if (body != null && !body.isEmpty()) {
                        bodySample = body.length() > 512 ? body.substring(0, 512) : body;
                    }
                } catch (Exception ignored) {
                }
                break;
            }
        }
        String combined = msg + " " + bodySample;

        // ↓↓↓ 具体错误翻译（优先级由高到低）↓↓↓

        // Ollama / 其他 provider 在模型不支持 function calling 时返回此文案：
        //   "<model> does not support tools"
        // 这不是模型坏，而是用户选错了模型 —— 给出可操作的切换建议。
        if (bodySample.contains("does not support tools") || msg.contains("does not support tools")) {
            return "当前模型不支持工具调用（function calling）。请在 设置 → 模型 里切换到支持 tools 的模型，"
                    + "例如 qwen3、qwen2.5:7b+、llama3.1:8b+、mistral-nemo、command-r 等。";
        }

        // DashScope "url error" is really "model name not mapped to any valid endpoint".
        if (msg.contains("url error") || msg.contains("[InvalidParameter]")
                || msg.contains("Model not exist") || msg.contains("model_not_found")
                || msg.contains("Model not found")
                || combined.contains("model not found") || combined.contains("not_found_error")) {
            return "Model name not available on this provider — verify the model exists and is supported (Settings → Models)";
        }
        // 对 Jackson 反序列化错误，提取关键信息
        if (msg.contains("engine_overloaded")) return "Model service overloaded, please retry later";
        if (msg.contains("unsupported image format") || msg.contains("unsupported")) return "Unsupported file format (e.g. SVG), use PNG/JPG instead";
        if (msg.contains("invalid_request_error") || msg.contains("400 Bad Request")) return "Bad request, please check input";
        if (msg.contains("rate_limit") || msg.contains("429")) return "Rate limit exceeded, please retry later";
        if (msg.contains("timeout") || msg.contains("Timeout")) return "Request timeout, please retry";
        if (msg.contains("502") || msg.contains("503") || msg.contains("504")) return "Model service temporarily unavailable";
        // 截断过长的原始消息
        return msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }

    /**
     * LLM 调用错误类型分类
     */
    public enum ErrorType {
        /** 无错误 */
        NONE,
        /** 速率限制 (429) */
        RATE_LIMIT,
        /** 服务端错误 (5xx, timeout) */
        SERVER_ERROR,
        /** Prompt 过长 (context length exceeded) */
        PROMPT_TOO_LONG,
        /** 认证错误 */
        AUTH_ERROR,
        /** 客户端错误 (400 Bad Request, 不支持的格式等) — 不应重试 */
        CLIENT_ERROR,
        /** Thinking 块错误（旧消息中的 thinking block 不可修改）— 可剥离后单次重试 */
        THINKING_BLOCK_ERROR,
        /**
         * LLM returned no content, no thinking, and no tool calls.
         * Treated as a soft failure — skip same-model retries and hand off to
         * the fallback chain directly. Typical cause: upstream rate-limit
         * rejection that comes back as HTTP 200 with empty body.
         */
        EMPTY_RESPONSE,
        /**
         * payment / billing failure (HTTP 402, "insufficient_quota",
         * "credit balance is too low", etc.). Distinct from {@link #AUTH_ERROR}
         * because the right response is to <i>switch provider</i> (a different
         * provider may have credits) rather than just terminate. Skips same-model
         * retries and falls through to the fallback chain.
         */
        BILLING,
        /**
         * requested model id not recognized by the provider
         * (HTTP 404, "Model not exist", "model_not_found", DashScope's
         * "url error"). Same handling as {@link #BILLING} — heads straight
         * to the fallback chain instead of looping retries against a model
         * that does not exist.
         */
        MODEL_NOT_FOUND,
        /** 其他未知错误 */
        UNKNOWN
    }

    /**
     * 流式调用结果
     */
    public record StreamResult(
            /** 完整内容文本 */
            String text,
            /** 完整 thinking 文本 */
            String thinking,
            /** 重建的完整 AssistantMessage（含 toolCalls） */
            AssistantMessage assistantMessage,
            /** 完整工具调用列表 */
            List<AssistantMessage.ToolCall> toolCalls,
            /** 是否包含工具调用 */
            boolean hasToolCalls,
            /** 本次调用消耗的 prompt tokens */
            int promptTokens,
            /** 本次调用消耗的 completion tokens */
            int completionTokens,
            /** 结果是否不完整（LLM 中途断开但已有部分内容） */
            boolean partial,
            /** 错误信息（非空表示调用失败，但可能仍有 partial 内容可用） */
            String errorMessage,
            /** 错误类型分类 */
            ErrorType errorType,
            /** 用户主动停止（stopRequested）导致的提前返回 */
            boolean stopped,
            /** RFC-014: Anthropic prompt cache 命中字节数（其它 provider 为 0） */
            int cacheReadTokens,
            /** RFC-014: Anthropic prompt cache 写入字节数（其它 provider 为 0） */
            int cacheWriteTokens
    ) {
        /** 兼容旧调用方 — 无 partial/error/stopped 的正常结果 */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, false, null, ErrorType.NONE, false, 0, 0);
        }

        /** 兼容 10-arg 调用点 */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens,
                            boolean partial, String errorMessage, ErrorType errorType) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, partial, errorMessage, errorType, false, 0, 0);
        }

        /** 兼容 12-arg 调用点（pre-RFC-014） */
        public StreamResult(String text, String thinking, AssistantMessage assistantMessage,
                            List<AssistantMessage.ToolCall> toolCalls, boolean hasToolCalls,
                            int promptTokens, int completionTokens,
                            boolean partial, String errorMessage, ErrorType errorType,
                            boolean stopped) {
            this(text, thinking, assistantMessage, toolCalls, hasToolCalls,
                    promptTokens, completionTokens, partial, errorMessage, errorType, stopped, 0, 0);
        }

        /** 是否有不可忽略的错误（无内容 + 有错误） */
        public boolean hasFatalError() {
            return errorMessage != null && (text == null || text.isBlank()) && !hasToolCalls;
        }

        /** 是否为 Prompt 过长错误 */
        public boolean isPromptTooLong() {
            return errorType == ErrorType.PROMPT_TOO_LONG;
        }

        /** 是否有任何可保存的内容（text/thinking/toolCalls） */
        public boolean hasAnyContent() {
            return (text != null && !text.isBlank())
                    || (thinking != null && !thinking.isBlank())
                    || hasToolCalls;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 从 AssistantMessage 的 properties 中提取 reasoningContent
     * <p>
     * Spring AI 1.1.3 的 OpenAiChatModel 在流式路径中会将 delta.reasoning_content
     * 放入 properties 的 "reasoningContent" key。
     */
    private String extractReasoningContent(AssistantMessage msg) {
        Map<String, Object> metadata = msg.getMetadata();
        if (metadata == null) {
            return null;
        }
        Object rc = metadata.get("reasoningContent");
        if (rc instanceof String s && !s.isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * 广播 delta 事件（content_delta / thinking_delta）
     */
    private void broadcastDelta(String conversationId, String eventName, String delta) {
        if (streamTracker == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        // 手动构建 JSON 避免序列化开销，格式与 ChatController.broadcastEvent 一致
        String json = buildDeltaJson(delta);
        streamTracker.broadcast(conversationId, eventName, json);
    }

    /**
     * 构建 {"delta":"..."} JSON
     */
    private static String buildDeltaJson(String delta) {
        StringBuilder sb = new StringBuilder("{\"delta\":\"");
        for (int k = 0; k < delta.length(); k++) {
            char c = delta.charAt(k);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        sb.append("\"}");
        return sb.toString();
    }

    /**
     * 累积 tool call 分片。
     * <p>
     * 流式模式下 tool calls 可能分多个 chunk 到来：
     * - 第一个 chunk 携带 id、name 和部分 arguments
     * - 后续 chunk 只有 arguments 增量
     * <p>
     * 采用增量累积方式合并分片 tool_call。
     */
    private void accumulateToolCalls(List<AssistantMessage.ToolCall> chunkToolCalls,
                                     List<ToolCallAccumulator> accumulators) {
        for (AssistantMessage.ToolCall tc : chunkToolCalls) {
            if (tc.id() != null && !tc.id().isEmpty()) {
                // 新的 tool call 或完整 tool call
                ToolCallAccumulator existing = findAccumulator(accumulators, tc.id());
                if (existing != null) {
                    // 追加 arguments
                    if (tc.arguments() != null) {
                        existing.arguments.append(tc.arguments());
                    }
                } else {
                    ToolCallAccumulator acc = new ToolCallAccumulator();
                    acc.id = tc.id();
                    acc.type = tc.type();
                    acc.name = tc.name();
                    acc.arguments = new StringBuilder(tc.arguments() != null ? tc.arguments() : "");
                    accumulators.add(acc);
                }
            } else if (!accumulators.isEmpty()) {
                // 无 id 的 chunk，追加到最后一个 accumulator 的 arguments
                ToolCallAccumulator last = accumulators.get(accumulators.size() - 1);
                if (tc.arguments() != null) {
                    last.arguments.append(tc.arguments());
                }
                if (tc.name() != null && !tc.name().isEmpty() && (last.name == null || last.name.isEmpty())) {
                    last.name = tc.name();
                }
            }
        }
    }

    private ToolCallAccumulator findAccumulator(List<ToolCallAccumulator> accumulators, String id) {
        for (ToolCallAccumulator acc : accumulators) {
            if (id.equals(acc.id)) {
                return acc;
            }
        }
        return null;
    }

    private List<AssistantMessage.ToolCall> buildFinalToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            result.add(new AssistantMessage.ToolCall(
                    acc.id,
                    acc.type != null ? acc.type : "function",
                    acc.name,
                    acc.arguments.toString()));
        }
        return result;
    }

    private static class ToolCallAccumulator {
        String id;
        String type;
        String name;
        StringBuilder arguments = new StringBuilder();
    }

    // ==================== <think> 标签 fallback 解析 ====================

    private record ThinkExtracted(String thinking, String content) {}

    /**
     * 从内容中提取 &lt;think&gt;...&lt;/think&gt; 标签内的文本作为 thinking。
     * 仅作为 fallback，当模型不支持结构化 reasoningContent 时使用。
     */
    private static ThinkExtracted extractThinkTags(String content) {
        StringBuilder thinking = new StringBuilder();
        StringBuilder cleaned = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            int tagStart = content.indexOf("<think>", i);
            if (tagStart < 0) {
                cleaned.append(content, i, content.length());
                break;
            }
            cleaned.append(content, i, tagStart);
            int tagEnd = content.indexOf("</think>", tagStart);
            if (tagEnd < 0) {
                // 未闭合的 <think> 标签，将剩余部分视为 thinking
                thinking.append(content, tagStart + 7, content.length());
                break;
            }
            thinking.append(content, tagStart + 7, tagEnd);
            i = tagEnd + 8;
        }
        return new ThinkExtracted(thinking.toString().trim(), cleaned.toString().trim());
    }
}
