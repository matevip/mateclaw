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
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

    /** 最大工具调用迭代次数 */
    protected int maxIterations = 10;

    /** 模型名称 */
    protected String modelName;

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
        List<MessageEntity> history = conversationService.listMessages(conversationId);
        if (history.isEmpty()) {
            return List.of();
        }

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
            MessageEntity entity = history.get(i);
            // 过滤审批占位消息，确保 LLM 上下文不包含审批残留
            if ("assistant".equals(entity.getRole()) && isApprovalPlaceholder(entity.getContent())) {
                log.debug("[{}] Filtering approval placeholder from history: msgId={}", agentName, entity.getId());
                continue;
            }
            Message springMessage = toSpringMessage(entity);
            if (springMessage != null) {
                messages.add(springMessage);
            }
        }
        return messages;
    }

    /**
     * 判断是否为审批占位消息（委托给共享工具类）
     */
    static boolean isApprovalPlaceholder(String content) {
        return ApprovalPlaceholderUtil.isApprovalPlaceholder(content);
    }

    private Message toSpringMessage(MessageEntity message) {
        if (message == null) {
            return null;
        }
        String renderedContent = conversationService.renderMessageContent(message);
        if (renderedContent == null || renderedContent.isBlank()) {
            return null;
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
     * 仅已知支持视频分析的视觉模型（Qwen-VL、GPT-4o、Gemini 等）才注入视频 Media。
     */
    private boolean modelSupportsVideo() {
        if (modelName == null) return false;
        String n = modelName.toLowerCase();
        return (n.contains("qwen") && n.contains("vl"))
                || n.contains("gpt-4o")
                || n.contains("gemini")
                || (n.contains("glm") && n.contains("v"));
    }

    /**
     * 构建 UserMessage，支持 multimodal：如果消息包含图片/视频附件，直接注入 Spring AI Media 对象，
     * 让模型在 prompt 中直接看到媒体内容，不需要再调 MCP read_media_file 工具。
     */
    protected UserMessage buildUserMessage(MessageEntity message, String renderedContent) {
        List<MessageContentPart> parts = conversationService.parseMessageParts(message);
        List<Media> mediaList = new ArrayList<>();
        boolean videoSupported = modelSupportsVideo();

        for (MessageContentPart part : parts) {
            if (part == null) continue;
            String partType = part.getType();
            String contentType = part.getContentType();
            if (contentType == null) continue;

            boolean isImage = "file".equals(partType) && contentType.startsWith("image/");
            boolean isVideo = ("video".equals(partType) || "file".equals(partType)) && contentType.startsWith("video/");

            if (!isImage && !isVideo) continue;

            // SVG 是 XML 文本，不是光栅图片，LLM multimodal API 不支持
            if (isImage && contentType.contains("svg")) {
                log.debug("[{}] Skipping SVG attachment (not supported by multimodal API): {}",
                        agentName, part.getFileName());
                continue;
            }

            // 视频仅在模型支持时注入，否则跳过（避免发送给非视觉模型导致 400 错误）
            if (isVideo && !videoSupported) {
                log.debug("[{}] Skipping video attachment (model '{}' does not support video): {}",
                        agentName, modelName, part.getFileName());
                continue;
            }

            // 视频文件大小保护
            if (isVideo && part.getFileSize() != null && part.getFileSize() > MAX_VIDEO_SIZE_BYTES) {
                log.warn("[{}] Skipping oversized video attachment ({}MB > 20MB): {}",
                        agentName, part.getFileSize() / (1024 * 1024), part.getFileName());
                continue;
            }

            // 解析媒体文件路径：先尝试原始 path，再尝试拼接工作目录
            Path mediaPath = resolveImagePath(part.getPath());
            if (mediaPath == null) {
                log.warn("[{}] {} file not found for attachment: {}, path: {}",
                        agentName, isVideo ? "Video" : "Image", part.getFileName(), part.getPath());
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
            }
        }

        if (mediaList.isEmpty()) {
            return new UserMessage(renderedContent);
        }
        return UserMessage.builder()
                .text(renderedContent)
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
