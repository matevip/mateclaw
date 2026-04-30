package vip.mate.agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import vip.mate.approval.ApprovalPlaceholderUtil;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 抽象基类
 * 定义所有 Agent 的基础行为与状态管理
 *
 * @author MateClaw Team
 */
@Slf4j
public abstract class BaseAgent {

    protected final ChatClient chatClient;
    protected final ConversationService conversationService;
    protected final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);

    /** Agent 唯一标识 */
    protected String agentId;

    /** Agent 名称 */
    protected String agentName;

    /** 系统提示词 */
    protected String systemPrompt;

    /**
     * Max ReAct iterations (one reasoning + action + observation step counts as one).
     * Default 100, hard ceiling 100 (enforced in AgentGraphBuilder so per-agent DB
     * overrides cannot exceed it). Aligned with QwenPaw's _MAX_MAX_ITERATIONS.
     */
    public static final int MAX_ITERATIONS_HARD_CEILING = 100;
    protected int maxIterations = 100;

    /** 工作区活动目录（限制文件工具访问范围，为空不限制） */
    protected String workspaceBasePath;

    /** 模型名称 */
    protected String modelName;

    /**
     * Modalities the chat model can natively consume (resolved at agent build time
     * by {@link vip.mate.llm.service.ModelCapabilityService}). Empty set = unknown model,
     * fall back to text-only behavior. See issue #44.
     */
    protected Set<ModelCapabilityService.Modality> modelCapabilities = EnumSet.noneOf(ModelCapabilityService.Modality.class);

    /** 采样温度 */
    protected Double temperature;

    /** 最大输出 token */
    protected Integer maxTokens;

    /** 最大输入 token（上下文窗口） */
    protected Integer maxInputTokens;

    /** Top P */
    protected Double topP;

    /** 当前运行时是否启用工具调用 */
    protected boolean toolCallingEnabled = true;

    /** 构建时使用的 provider ID（运行时快照） */
    protected String runtimeProviderId;


    protected BaseAgent(ChatClient chatClient, ConversationService conversationService) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
    }

    /**
     * 同步对话接口
     *
     * @param userMessage    用户消息
     * @param conversationId 会话ID
     * @return 助手回复
     */
    public abstract String chat(String userMessage, String conversationId);

    /**
     * 流式对话接口（SSE）
     *
     * @param userMessage    用户消息
     * @param conversationId 会话ID
     * @return 流式文本 Flux
     */
    public abstract Flux<String> chatStream(String userMessage, String conversationId);

    /**
     * 执行复杂任务（Plan-and-Execute 模式）
     *
     * @param goal           任务目标
     * @param conversationId 会话ID
     * @return 执行结果摘要
     */
    public abstract String execute(String goal, String conversationId);

    /**
     * 带工具重放的对话接口（审批通过后调用）
     * <p>
     * 默认实现退化为普通 chat，子类可覆盖注入 forced_tool_call。
     *
     * @param userMessage      用户消息
     * @param conversationId   会话 ID
     * @param toolCallPayload  要重放的工具调用 JSON
     * @return 助手回复
     */
    public String chatWithReplay(String userMessage, String conversationId, String toolCallPayload) {
        return chat(userMessage, conversationId);
    }

    /**
     * 带工具重放的流式对话接口（Web 端审批通过后调用）
     */
    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload) {
        return chatWithReplayStream(userMessage, conversationId, toolCallPayload, "");
    }

    public Flux<AgentService.StreamDelta> chatWithReplayStream(String userMessage, String conversationId,
                                                                String toolCallPayload, String requesterId) {
        if (this instanceof StructuredStreamCapable capable) {
            return capable.chatStructuredStream(userMessage, conversationId, requesterId);
        }
        return chatStream(userMessage, conversationId)
                .map(chunk -> new AgentService.StreamDelta(chunk, null));
    }

    /**
     * 获取当前 Agent 状态
     */
    public AgentState getState() {
        return state.get();
    }

    /**
     * 设置 Agent 状态
     */
    protected void setState(AgentState newState) {
        AgentState old = state.getAndSet(newState);
        log.debug("[{}] Agent state: {} -> {}", agentName, old, newState);
    }

    /**
     * 判断 Agent 是否空闲
     */
    public boolean isIdle() {
        return AgentState.IDLE.equals(state.get());
    }

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getSystemPrompt() { return systemPrompt; }

    protected ChatClient.ChatClientRequestSpec createConversationRequest(String userMessage, String conversationId) {
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(systemPrompt != null ? systemPrompt : "你是一个有帮助的AI助手。");

        List<Message> historyMessages = buildConversationHistory(conversationId, userMessage);
        if (!historyMessages.isEmpty()) {
            request = request.messages(historyMessages);
        }
        return request.user(userMessage);
    }

    protected List<Message> buildConversationHistory(String conversationId, String currentUserMessage) {
        // ===== 两阶段加载：短对话全量，长对话分页（递进式） =====
        long totalCount = conversationService.countMessages(conversationId);
        if (totalCount <= 0) {
            return List.of();
        }

        int windowSize = getEffectiveWindowSize();
        List<MessageEntity> history;

        if (totalCount <= windowSize) {
            // 短对话：全量加载（与旧逻辑一致）
            history = conversationService.listMessages(conversationId);
        } else {
            // 长对话：只加载最近 windowSize 条
            history = conversationService.listRecentMessages(conversationId, windowSize);
            log.info("[{}] Progressive load: {} of {} messages (window={})",
                    agentName, history.size(), totalCount, windowSize);
        }

        // ===== 识别持久化的压缩摘要：从摘要位置开始，跳过更早消息 =====
        for (int i = 0; i < history.size(); i++) {
            MessageEntity msg = history.get(i);
            if ("system".equals(msg.getRole()) && isCompressionSummary(msg)) {
                history = new ArrayList<>(history.subList(i, history.size()));
                log.info("[{}] Found compression summary, loading from index {} ({} messages)",
                        agentName, i, history.size());
                break;
            }
        }

        // ===== 转换为 Spring AI Message 对象 =====
        int limit = history.size();
        if (limit > 0) {
            MessageEntity last = history.get(limit - 1);
            if ("user".equals(last.getRole()) && currentUserMessage.equals(last.getContent())) {
                limit -= 1;
            }
        }

        if (limit <= 0) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(limit);
        for (int i = 0; i < limit; i += 1) {
            Message springMessage = sanitizeForLlm(history.get(i));
            if (springMessage != null) {
                messages.add(springMessage);
            }
        }

        // Tail guard — orphan-user strip (issue #47).
        //
        // Invariant: every caller of buildConversationHistory appends the
        // current user message AFTER this history (BaseAgent.buildClient
        // via .user(), StateGraphReActAgent / StateGraphPlanExecuteAgent
        // via messages.add(buildCurrentUserMessage)). So the final prompt is
        //   [system, ...history, current_user]
        // and is *always* terminated by a user message. That means a trailing
        // assistant in history is FINE for every provider we support — it
        // produces the correct [..., user, assistant, current_user] alternation
        // (OpenAI, Anthropic, DeepSeek regular/thinking, Gemini, Qwen, …).
        //
        // The actual hazard is the opposite: a trailing USER in history.
        // That happens when the immediately-prior turn's assistant message
        // was dropped by Stage 1 (approval placeholder) or Stage 1.5 (errored
        // turn / "[错误] " row), or never persisted at all (turn interrupted
        // before doOnComplete saved the assistant). In that case the history
        // ends with an orphan unanswered user, and appending the current user
        // produces TWO consecutive user messages. Most providers concatenate
        // those and answer both — leaking the orphan question's answer
        // alongside the current answer. (This was the symptom reported in
        // issue #47, originally caused by a tail guard that stripped trailing
        // ASSISTANT messages instead of trailing USER ones — a direction-
        // reversed version of this loop.)
        //
        // Stripping orphan users is safe: the user re-asked or asked a new
        // question; the orphan turn produced no answer the model can build
        // on. We lose a small amount of conversational context in exchange
        // for clean alternation across every provider.
        while (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof UserMessage) {
            messages.remove(messages.size() - 1);
        }
        return messages;
    }

    /**
     * History sanitization entry point. Encapsulates *all* steps applied to a
     * persisted message before it reaches an LLM prompt. Returns {@code null}
     * to drop the message, or a Spring AI {@link Message} (possibly with
     * rewritten content) to keep it.
     *
     * <p>Design philosophy (OpenClaw-inspired): keep the conversion + every
     * sanitization stage centralized here so future steps (RFC-052 §9 PII
     * field-level redaction, RFC-049 thinking-block replay strategy, image
     * compression for vision models, etc.) plug in as additional inline
     * stages with clear ordering rather than scattering across the loop.
     *
     * <p>Current stages (in order):
     * <ol>
     *   <li><b>Drop approval placeholders</b> — assistant messages whose
     *       content is a "[等待审批]" stub from the approval flow are removed
     *       entirely so they don't pollute the LLM context.</li>
     *   <li><b>Render content</b> — convert {@code MessageEntity} to a string
     *       via {@link ConversationService#renderMessageContent}.</li>
     *   <li><b>Direct-tool scrub (RFC-052)</b> — assistant messages produced
     *       by a returnDirect tool path get their content replaced with a
     *       tool-named placeholder; the original DB content is unchanged.</li>
     *   <li><b>Type dispatch</b> — wrap into {@code AssistantMessage},
     *       {@code SystemMessage}, or {@code UserMessage} (with multimodal
     *       Media for image/video parts).</li>
     * </ol>
     */
    private Message sanitizeForLlm(MessageEntity entity) {
        if (entity == null) {
            return null;
        }

        // Stage 0: drop cron-run header rows (system role + "📋 " prefix)
        // inserted by CronJobLifecycleService.startRun. These are UI dividers
        // for the unified tasks_<wsId> view and the IM channel-session
        // mirror — they carry no semantic context for the LLM. Without this
        // skip, every subsequent IM turn would feed the model unsolicited
        // SystemMessage rows like "📋 每日新闻 · 定时触发 · 2026-04-30T10:55"
        // and bloat the prompt with scheduler metadata.
        if ("system".equals(entity.getRole())
                && entity.getContent() != null
                && entity.getContent().startsWith("📋 ")) {
            return null;
        }

        // Stage 1: drop approval-placeholder assistant messages
        if ("assistant".equals(entity.getRole()) && isApprovalPlaceholder(entity.getContent())) {
            log.debug("[{}] Filtering approval placeholder from history: msgId={}",
                    agentName, entity.getId());
            return null;
        }

        // Stage 1.5: drop typed-error assistant messages. These are persisted
        // by ChatController.doOnComplete with status='error' (or carry the
        // "[错误] " prefix injected by NodeStreamingChatHelper for legacy
        // rows). Re-sending them as multi-turn context drives a self-replicating
        // failure loop:
        //   - DeepSeek thinking mode → 400 "reasoning_content must be passed back"
        //     (we never captured a real reasoning_content for the failed turn)
        //   - Anthropic Claude → 400 "does not support assistant message prefill"
        //     (the trailing-user-dedup at the call site can leave an assistant
        //      tail when the prior turn errored)
        // Both providers' 400 then re-persist a fresh "[错误] " row, repeat.
        if ("assistant".equals(entity.getRole())
                && ("error".equals(entity.getStatus())
                        || (entity.getContent() != null && entity.getContent().startsWith("[错误] ")))) {
            log.debug("[{}] Filtering error assistant message from history: msgId={} status={}",
                    agentName, entity.getId(), entity.getStatus());
            return null;
        }

        // Delegate stages 2-4 to toSpringMessage; the stage 3 scrub is applied
        // there so the rendered content is replaced before the typed Message
        // wrapper is constructed.
        return toSpringMessage(entity);
    }

    /**
     * 判断消息是否为持久化的压缩摘要。
     */
    private boolean isCompressionSummary(MessageEntity msg) {
        return msg.getMetadata() != null && msg.getMetadata().contains("compression_summary");
    }

    /**
     * 动态计算窗口大小：基于模型上下文长度估算能容纳多少条消息。
     * 保守估算：每条消息平均 200 token，预留 30% 给系统提示词和当前消息。
     */
    private int getEffectiveWindowSize() {
        int contextTokens = maxInputTokens != null && maxInputTokens > 0
                ? maxInputTokens : 128000;
        int window = (int) (contextTokens * 0.7) / 200;
        return Math.max(20, Math.min(window, 500));
    }

    /**
     * 判断是否为审批占位消息（委托给共享工具类）
     */
    static boolean isApprovalPlaceholder(String content) {
        return ApprovalPlaceholderUtil.isApprovalPlaceholder(content);
    }

    /**
     * RFC-052: regex matching {@code "directToolNames":["a","b",...]} in the
     * metadata JSON and capturing every tool name in group(1) iterations. The
     * {@code \\s*} guards keep us robust to pretty-printed JSON.
     *
     * <p>Design note (OpenClaw-inspired): rather than a one-shot "is this a
     * direct turn?" boolean we extract the actual tool names and weave them
     * into the placeholder, so the next LLM turn can reason about *which* tool
     * answered (e.g. "the user just asked their salary; you used
     * query_employee_salary; if they ask follow-up questions, call it again").
     * This preserves conversational continuity that a generic placeholder
     * destroys.
     */
    private static final java.util.regex.Pattern DIRECT_TOOL_NAMES_ARRAY =
            java.util.regex.Pattern.compile(
                    "\"directToolNames\"\\s*:\\s*\\[(\\s*\"[^\"]*\"\\s*(?:,\\s*\"[^\"]*\"\\s*)*)\\]");
    private static final java.util.regex.Pattern DIRECT_TOOL_NAMES_INNER =
            java.util.regex.Pattern.compile("\"([^\"]+)\"");

    /**
     * RFC-052: returns the list of returnDirect tool names recorded in the
     * persisted assistant message's metadata. Empty list means this is NOT a
     * direct-tool message and the content is safe for the LLM.
     *
     * <p>Allocates only when a non-empty {@code directToolNames} array is
     * actually present (the common case — normal assistant turns — exits at
     * the first {@code contains} check with zero allocations).
     */
    static List<String> directToolNamesIn(MessageEntity msg) {
        if (msg == null) return List.of();
        String metadata = msg.getMetadata();
        if (metadata == null || metadata.isEmpty()) return List.of();
        if (!metadata.contains("\"directToolNames\"")) return List.of();
        java.util.regex.Matcher arrayMatcher = DIRECT_TOOL_NAMES_ARRAY.matcher(metadata);
        if (!arrayMatcher.find()) return List.of();
        String inner = arrayMatcher.group(1);
        java.util.regex.Matcher nameMatcher = DIRECT_TOOL_NAMES_INNER.matcher(inner);
        List<String> names = new ArrayList<>(2);
        while (nameMatcher.find()) {
            names.add(nameMatcher.group(1));
        }
        return names;
    }

    /**
     * Convenience wrapper preserved for callers that only need the boolean.
     * Keeps the original test surface stable.
     */
    static boolean isDirectToolMessage(MessageEntity msg) {
        return !directToolNamesIn(msg).isEmpty();
    }

    /**
     * RFC-052: build the placeholder text used to replace a direct-tool
     * assistant message in next-turn prompts. Includes the originating tool
     * names so the model retains conversational structure (it knows *why*
     * the content is redacted and *which* tool would re-fetch it). The
     * original message stays unchanged in {@code mate_message.content}.
     *
     * <p>Worded as a neutral status line, not as a faux assistant utterance —
     * the model treats it as a system-level note, not as previous output to
     * be continued.
     */
    static String directToolHistoryPlaceholder(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "[Previous answer was tool data returned directly to the user. " +
                    "Content withheld from model context per tool policy.]";
        }
        String joined = toolNames.size() == 1
                ? "'" + toolNames.get(0) + "'"
                : toolNames.stream()
                        .map(n -> "'" + n + "'")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
        return "[Previous turn used direct-return tool(s) " + joined + " to deliver " +
                "data straight to the user. Content withheld from model context per tool " +
                "policy. If the user asks a follow-up that requires that data, call the " +
                "tool again.]";
    }

    private Message toSpringMessage(MessageEntity message) {
        if (message == null) {
            return null;
        }
        String renderedContent = conversationService.renderMessageContent(message);
        if (renderedContent == null || renderedContent.isBlank()) {
            return null;
        }
        // RFC-052: scrub direct-tool content from any subsequent LLM prompt.
        // The DB content stays unchanged; only the in-memory Message handed to
        // the model gets replaced. This is MateClaw's persistence-aware analog
        // of joyagent-jdgenie's Memory.clearToolContext (purely in-memory) and
        // OpenClaw's stripToolResultDetails (structural strip per replay).
        //
        // Unlike a generic "withheld" placeholder, we name the originating
        // tool(s) so the model retains the dialog structure: it knows what
        // kind of data was withheld and which tool would fetch it again. This
        // preserves multi-turn coherence without leaking the payload itself.
        if ("assistant".equals(message.getRole())) {
            List<String> directNames = directToolNamesIn(message);
            if (!directNames.isEmpty()) {
                log.debug("[{}] Scrubbing direct-tool content from history msgId={} tools={} (RFC-052)",
                        agentName, message.getId(), directNames);
                renderedContent = directToolHistoryPlaceholder(directNames);
            }
        }
        return switch (message.getRole()) {
            case "assistant" -> new AssistantMessage(renderedContent);
            case "system" -> new SystemMessage(renderedContent);
            case "user" -> buildUserMessage(message, renderedContent);
            default -> null;
        };
    }

    private static final long MAX_VIDEO_SIZE_BYTES = 20 * 1024 * 1024; // 20MB

    /**
     * 判断当前模型是否支持视频输入。
     * 由 {@link ModelCapabilityService} 在 agent 构建时解析并注入到
     * {@link #modelCapabilities}，per-model 粒度（区分如 glm-4v vs glm-4v-plus）。
     */
    private boolean modelSupportsVideo() {
        return modelCapabilities.contains(ModelCapabilityService.Modality.VIDEO);
    }

    /**
     * 构建 UserMessage，支持 multimodal：如果消息包含图片/视频附件，直接注入 Spring AI Media 对象，
     * 让模型在 prompt 中直接看到媒体内容，不需要再调 MCP read_media_file 工具。
     */
    protected UserMessage buildUserMessage(MessageEntity message, String renderedContent) {
        List<MessageContentPart> parts = conversationService.parseMessageParts(message);
        List<Media> mediaList = new ArrayList<>();
        // Reasons for attachments that the model cannot consume — surfaced to the agent
        // via the user message text so it does not hallucinate a tool call to read them.
        // See issue #44.
        List<String> skippedAttachments = new ArrayList<>();
        boolean videoSupported = modelSupportsVideo();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            String partType = part.getType();
            String contentType = part.getContentType();
            // image 类型的 part 可能没有精确 contentType，补全为 image/jpeg
            if ("image".equals(partType) && (contentType == null || "image/*".equals(contentType))) {
                contentType = "image/jpeg";
            }
            if (contentType == null) continue;

            boolean isImage = ("image".equals(partType) || "file".equals(partType)) && contentType.startsWith("image/");
            boolean isVideo = ("video".equals(partType) || "file".equals(partType)) && contentType.startsWith("video/");

            if (!isImage && !isVideo) continue;

            // SVG 是 XML 文本，不是光栅图片，LLM multimodal API 不支持
            if (isImage && contentType.contains("svg")) {
                log.debug("[{}] Skipping SVG attachment (not supported by multimodal API): {}",
                        agentName, part.getFileName());
                skippedAttachments.add(part.getFileName() + "(SVG 格式，多模态 API 不支持)");
                continue;
            }

            // 视频仅在模型支持时注入，否则跳过（避免发送给非视觉模型导致 400 错误）
            if (isVideo && !videoSupported) {
                log.debug("[{}] Skipping video attachment (model '{}' does not support video): {}",
                        agentName, modelName, part.getFileName());
                skippedAttachments.add(part.getFileName() + "(当前模型未声明视频能力)");
                continue;
            }

            // 视频文件大小保护
            if (isVideo && part.getFileSize() != null && part.getFileSize() > MAX_VIDEO_SIZE_BYTES) {
                log.warn("[{}] Skipping oversized video attachment ({}MB > 20MB): {}",
                        agentName, part.getFileSize() / (1024 * 1024), part.getFileName());
                skippedAttachments.add(part.getFileName() + "(视频超过 20MB 大小限制)");
                continue;
            }

            // 解析媒体文件路径：先尝试 path，再尝试 mediaId（IM 渠道下载后存在 mediaId 中），再拼接工作目录
            Path mediaPath = resolveImagePath(part.getPath());
            if (mediaPath == null && part.getMediaId() != null) {
                mediaPath = resolveImagePath(part.getMediaId());
            }
            if (mediaPath == null) {
                log.warn("[{}] {} file not found for attachment: {}, path: {}, mediaId: {}",
                        agentName, isVideo ? "Video" : "Image", part.getFileName(), part.getPath(), part.getMediaId());
                skippedAttachments.add(part.getFileName() + "(文件未找到)");
                continue;
            }
            try {
                MimeType mimeType = MimeType.valueOf(contentType);
                Media media = new Media(mimeType, new FileSystemResource(mediaPath));
                mediaList.add(media);
                log.debug("[{}] Injected {} into prompt: {} ({})",
                        agentName, isVideo ? "video" : "image", part.getFileName(), mediaPath);
            } catch (Exception e) {
                log.warn("[{}] Failed to create Media for {} {}: {}",
                        agentName, isVideo ? "video" : "image", part.getFileName(), e.getMessage());
                skippedAttachments.add(part.getFileName() + "(媒体加载失败)");
            }
        }

        String finalText = renderedContent;
        if (!skippedAttachments.isEmpty()) {
            finalText = (renderedContent == null ? "" : renderedContent)
                    + "\n\n[系统提示] 以下附件未传入模型：" + String.join("、", skippedAttachments)
                    + "。请直接告知用户具体原因，不要尝试用其他工具读取这些文件。";
        }

        if (mediaList.isEmpty()) {
            return new UserMessage(finalText);
        }
        return UserMessage.builder()
                .text(finalText)
                .media(mediaList)
                .build();
    }

    /**
     * 解析图片文件的绝对路径。
     * <p>
     * 上传文件存储在 data/chat-uploads/ 下，是相对于 Spring Boot 工作目录的路径。
     * MCP 工具的工作目录可能不同，所以这里直接解析为绝对路径。
     */
    /**
     * 构建当前用户消息的 UserMessage（含 multimodal 图片注入）。
     * <p>
     * 从 DB 读取最后一条 user 消息的 contentParts，提取图片附件并注入 Media。
     * 不依赖文本相等匹配（避免重复文本误绑定到错误轮次），而是直接取最后一条 user 消息，
     * 因为 buildInitialState 在 saveMessage 之后调用，最后一条 user 消息就是当前消息。
     *
     * @param conversationId 会话 ID
     * @param userMessageText 用户消息文本（作为 fallback 内容）
     * @return 带图片 Media 的 UserMessage（如果有图片附件），否则纯文本 UserMessage
     */
    protected UserMessage buildCurrentUserMessage(String conversationId, String userMessageText) {
        try {
            List<MessageEntity> history = conversationService.listMessages(conversationId);
            // 倒序取最后一条 user 消息（buildInitialState 在 saveMessage 后调用，所以最后一条就是当前消息）
            for (int i = history.size() - 1; i >= 0; i--) {
                MessageEntity msg = history.get(i);
                if ("user".equals(msg.getRole())) {
                    // 用 DB 中的实际内容（可能包含 contentParts），不用传入的 text
                    String content = conversationService.renderMessageContent(msg);
                    return buildUserMessage(msg, content != null && !content.isBlank() ? content : userMessageText);
                }
            }
        } catch (Exception e) {
            log.debug("[{}] Failed to load current user message parts for multimodal: {}",
                    agentName, e.getMessage());
        }
        return new UserMessage(userMessageText);
    }

    protected Path resolveImagePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        // 1. 如果已经是绝对路径且存在，直接用
        Path path = Paths.get(relativePath);
        if (path.isAbsolute() && Files.exists(path)) {
            return path;
        }
        // 2. 相对于 Spring Boot 工作目录解析
        Path resolved = Paths.get(System.getProperty("user.dir")).resolve(relativePath);
        if (Files.exists(resolved)) {
            return resolved;
        }
        // 3. 都找不到
        log.debug("[{}] Image path not found: tried {} and {}", agentName, path, resolved);
        return null;
    }

}
