package vip.mate.channel.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.StreamingChannelAdapter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉渠道适配器
 * <p>
 * 支持两种接入模式：
 * - <b>Stream 模式（推荐）</b>：WebSocket 长连接，无需公网 IP，钉钉官方推荐
 * - <b>Webhook 模式</b>：HTTP 回调，需要公网可访问的 URL
 * <p>
 * 消息格式：
 * - <b>markdown</b>：普通 Markdown 消息
 * - <b>card</b>：AI Card 流式卡片（需配置 card_template_id）
 * <p>
 * 配置项（configJson）：
 * - connection_mode: 接入模式（stream / webhook），默认 stream
 * - client_id: 钉钉应用 AppKey
 * - client_secret: 钉钉应用 AppSecret
 * - message_type: 消息格式（markdown / card），默认 markdown
 * - card_template_id: AI Card 模板 ID（message_type=card 时必填）
 * - robot_code: 机器人编码（card 模式群聊建议配置）
 *
 * @author MateClaw Team
 */
@Slf4j
public class DingTalkChannelAdapter extends AbstractChannelAdapter implements StreamingChannelAdapter {

    public static final String CHANNEL_TYPE = "dingtalk";

    private HttpClient httpClient;

    /** 钉钉 Stream 客户端（Stream 模式下使用） */
    private OpenDingTalkClient streamClient;

    /** AI Card 管理器（message_type=card 时初始化） */
    private DingTalkAICardManager aiCardManager;

    public DingTalkChannelAdapter(ChannelEntity channelEntity,
                                  ChannelMessageRouter messageRouter,
                                  ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        // 钉钉 Stream 重连：2s→4s→8s→16s→30s，无限重试
        this.backoff = new ExponentialBackoff(2000, 30000, 2.0, -1);
    }

    /**
     * 获取接入模式：stream（默认，推荐） 或 webhook
     */
    public String getConnectionMode() {
        return getConfigString("connection_mode", "stream");
    }

    /**
     * 是否为 Stream 长连接模式
     */
    public boolean isStreamMode() {
        return "stream".equals(getConnectionMode());
    }

    @Override
    protected void doStart() {
        String clientId = getConfigString("client_id");
        String clientSecret = getConfigString("client_secret");

        if (clientId == null || clientSecret == null) {
            throw new IllegalStateException("DingTalk channel requires client_id and client_secret in configJson");
        }

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // 初始化 AI Card 管理器（message_type=card 且配置了模板 ID）
        String cardTemplateId = getConfigString("card_template_id");
        String messageType = getConfigString("message_type", "markdown");
        if ("card".equals(messageType) && cardTemplateId != null && !cardTemplateId.isBlank()) {
            this.aiCardManager = new DingTalkAICardManager(httpClient, objectMapper, clientId, clientSecret);
            log.info("[dingtalk] AI Card enabled: templateId={}", cardTemplateId);
        }

        // 启动 Stream 模式或 Webhook 模式
        if (isStreamMode()) {
            startStreamMode(clientId, clientSecret);
        } else {
            log.info("[dingtalk] Webhook mode: waiting for callbacks at /api/v1/channels/webhook/dingtalk");
        }

        log.info("[dingtalk] DingTalk channel initialized: mode={}, clientId={}, robotCode={}, aiCard={}",
                getConnectionMode(), clientId, getConfigString("robot_code"), isAICardEnabled());
    }

    /**
     * 启动 Stream 长连接模式
     * <p>
     * 使用钉钉 Stream SDK（dingtalk-stream）建立 WebSocket 长连接，
     * 通过 {@link OpenDingTalkCallbackListener} 回调接收机器人消息，无需公网 IP。
     * <p>
     * SDK 内部自带断线重连机制。
     */
    private void startStreamMode(String clientId, String clientSecret) {
        try {
            OpenDingTalkCallbackListener<ChatbotMessage, Void> botListener = message -> {
                try {
                    handleStreamMessage(message);
                } catch (Exception e) {
                    log.error("[dingtalk-stream] Failed to handle message: {}", e.getMessage(), e);
                }
                return null;
            };

            this.streamClient = OpenDingTalkStreamClientBuilder.custom()
                    .credential(new AuthClientCredential(clientId, clientSecret))
                    .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, botListener)
                    .build();
            streamClient.start();
            log.info("[dingtalk-stream] Stream connection established (no public IP needed)");
        } catch (Exception e) {
            log.error("[dingtalk-stream] Failed to start stream client: {}", e.getMessage(), e);
            throw new RuntimeException("DingTalk Stream start failed: " + e.getMessage(), e);
        }
    }

    /**
     * 处理 Stream 模式收到的机器人消息
     * <p>
     * 从 SDK 的 {@link ChatbotMessage} 提取字段，构建与 Webhook 兼容的 payload Map，
     * 复用 {@link #handleWebhook(Map)} 进行统一处理。
     */
    private void handleStreamMessage(ChatbotMessage msg) {
        try {
            // 构建与 Webhook payload 格式兼容的 Map，复用已有解析逻辑
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("msgId", msg.getMsgId());
            payload.put("senderStaffId", msg.getSenderStaffId());
            payload.put("senderId", msg.getSenderId());
            payload.put("senderNick", msg.getSenderNick());
            payload.put("conversationId", msg.getConversationId());
            payload.put("conversationType", msg.getConversationType());
            payload.put("sessionWebhook", msg.getSessionWebhook());

            // 消息内容
            // 钉钉服务端已经把语音转写好放在 MessageContent.recognition 里（跟
            // 企业微信 voice.content 一个模式），不需要 STT。优先读 recognition；
            // 否则读 text.content。其他复杂类型（picture / richText）暂由
            // handleWebhook 内部处理 —— 但 stream 模式下我们目前没把那些类型
            // 的字段塞进 payload，是个遗留待修项（picture / richText 同样会掉消息）。
            String recognition = msg.getContent() != null ? msg.getContent().getRecognition() : null;
            if (recognition != null && !recognition.isBlank()) {
                payload.put("msgtype", "audio");
                payload.put("audio", Map.of("recognition", recognition));
            } else if (msg.getText() != null) {
                payload.put("msgtype", "text");
                payload.put("text", Map.of("content", msg.getText().getContent() != null ? msg.getText().getContent() : ""));
            }

            handleWebhook(payload);
        } catch (Exception e) {
            log.error("[dingtalk-stream] Failed to parse stream message: {}", e.getMessage(), e);
        }
    }

    @Override
    protected void doStop() {
        // 关闭 Stream 客户端
        if (streamClient != null) {
            try {
                streamClient.stop();
                log.info("[dingtalk-stream] Stream client stopped");
            } catch (Exception e) {
                log.warn("[dingtalk-stream] Error stopping stream client: {}", e.getMessage());
            }
            streamClient = null;
        }
        if (aiCardManager != null) {
            aiCardManager.cleanup();
            aiCardManager = null;
        }
        this.httpClient = null;
        log.info("[dingtalk] DingTalk channel stopped");
    }

    // ==================== AI Card ====================

    /**
     * 是否启用了 AI Card 流式输出
     * <p>
     * 当 message_type=card 且 card_template_id 已配置时启用
     */
    public boolean isAICardEnabled() {
        return aiCardManager != null
                && "card".equals(getConfigString("message_type"))
                && getConfigString("card_template_id") != null;
    }

    /**
     * 获取 AI Card 管理器
     */
    public DingTalkAICardManager getAICardManager() {
        return aiCardManager;
    }

    /**
     * 获取 AI Card 模板 ID
     */
    public String getCardTemplateId() {
        return getConfigString("card_template_id");
    }

    /**
     * 获取机器人编码
     */
    public String getRobotCode() {
        return getConfigString("robot_code");
    }

    // ==================== StreamingChannelAdapter ====================

    /**
     * 流式处理 Agent 事件并渲染到钉钉
     * <p>
     * 渲染策略：
     * - AI Card 启用时：创建卡片 → 流式更新 → 完成/失败
     * - AI Card 未启用时：累积全部内容后通过 sessionWebhook 一次性发送
     */
    @Override
    public String processStream(Flux<StreamDelta> stream, ChannelMessage message, String conversationId) {
        if (isAICardEnabled()) {
            return processStreamWithAICard(stream, message);
        }
        // 无 AI Card：累积后发送（退化为文本模式，但仍走 streaming 获取内容）
        return processStreamAsText(stream, message);
    }

    /**
     * AI Card 流式渲染路径
     * <p>
     * 参考 MateClaw 的 _process_dingtalk_core() 模式：
     * 1. 创建"思考中..."卡片
     * 2. 消费事件流，流式更新卡片（500ms 节流）
     * 3. 完成时标记 FINISHED，异常时标记 FAILED
     * 4. 卡片创建失败时退化为文本模式
     */
    private String processStreamWithAICard(Flux<StreamDelta> stream, ChannelMessage message) {
        String cardTemplateId = getCardTemplateId();
        String robotCode = getRobotCode();
        String chatType = message.getChatId() != null ? "2" : "1";
        String dtConversationId = extractDingTalkConversationId(message);

        // Step 1: 创建并投放"思考中..."卡片
        String outTrackId = aiCardManager.createAndDeliverCard(
                cardTemplateId, dtConversationId, chatType, robotCode);

        if (outTrackId == null) {
            log.warn("[dingtalk] AI Card creation failed, falling back to text mode");
            return processStreamAsText(stream, message);
        }

        log.info("[dingtalk] AI Card streaming started: outTrackId={}", outTrackId);

        // Step 2: 消费事件流，流式更新卡片
        StringBuilder contentAccumulator = new StringBuilder();
        try {
            stream.doOnNext(delta -> {
                        if (delta.content() != null) {
                            contentAccumulator.append(delta.content());
                            aiCardManager.appendContent(outTrackId, delta.content(), false);
                        }
                    })
                    .doOnError(error -> {
                        log.error("[dingtalk] AI Card stream error: outTrackId={}, error={}",
                                outTrackId, error.getMessage());
                        aiCardManager.failCard(outTrackId, error.getMessage());
                    })
                    .blockLast(Duration.ofMinutes(5));

            // Step 3: 完成
            String finalContent = contentAccumulator.toString();
            if (finalContent.isBlank()) {
                finalContent = "（无回复内容）";
            }
            aiCardManager.finishCard(outTrackId, finalContent);

            log.info("[dingtalk] AI Card streaming completed: outTrackId={}, contentLen={}",
                    outTrackId, finalContent.length());
            return finalContent;

        } catch (Exception e) {
            log.error("[dingtalk] AI Card streaming failed: outTrackId={}, error={}",
                    outTrackId, e.getMessage(), e);
            aiCardManager.failCard(outTrackId, e.getMessage());

            String partial = contentAccumulator.toString();
            if (!partial.isBlank()) {
                return partial;
            }
            throw new RuntimeException("AI Card streaming failed: " + e.getMessage(), e);
        }
    }

    /**
     * 文本模式流式处理：累积全部内容后通过 renderAndSend 发送
     */
    private String processStreamAsText(Flux<StreamDelta> stream, ChannelMessage message) {
        StringBuilder contentAccumulator = new StringBuilder();

        stream.doOnNext(delta -> {
                    if (delta.content() != null) {
                        contentAccumulator.append(delta.content());
                    }
                })
                .blockLast(Duration.ofMinutes(5));

        String finalContent = contentAccumulator.toString();
        if (!finalContent.isBlank()) {
            String replyTarget = message.getReplyToken() != null ? message.getReplyToken()
                    : (message.getChatId() != null ? message.getChatId() : message.getSenderId());
            renderAndSend(replyTarget, finalContent);
        }
        return finalContent;
    }

    /**
     * 从 rawPayload 中提取钉钉原生 conversationId
     */
    @SuppressWarnings("unchecked")
    private String extractDingTalkConversationId(ChannelMessage message) {
        if (message.getRawPayload() instanceof Map<?, ?> payload) {
            Object convId = payload.get("conversationId");
            if (convId instanceof String s) {
                return s;
            }
        }
        return message.getChatId() != null ? message.getChatId() : message.getSenderId();
    }

    /**
     * 处理来自钉钉 Webhook 的回调消息
     * 由 ChannelWebhookController 调用
     */
    @SuppressWarnings("unchecked")
    public void handleWebhook(Map<String, Object> payload) {
        try {
            String msgtype = (String) payload.get("msgtype");
            List<MessageContentPart> contentParts = new ArrayList<>();
            String textContent = null;

            if ("richText".equals(msgtype)) {
                // richText 消息：可包含文本 + 图片
                Map<String, Object> richTextBody = (Map<String, Object>) payload.get("richText");
                if (richTextBody != null) {
                    List<Map<String, Object>> richTextList = (List<Map<String, Object>>) richTextBody.get("richText");
                    if (richTextList != null) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (Map<String, Object> item : richTextList) {
                            String text = (String) item.get("text");
                            if (text != null && !text.isBlank()) {
                                contentParts.add(MessageContentPart.text(text));
                                textBuilder.append(text);
                            }
                            String downloadCode = (String) item.get("downloadCode");
                            String pictureUrl = (String) item.get("pictureUrl");
                            if (downloadCode != null || pictureUrl != null) {
                                contentParts.add(MessageContentPart.image(downloadCode, pictureUrl));
                            }
                        }
                        textContent = textBuilder.toString().trim();
                    }
                }
            } else if ("audio".equals(msgtype)) {
                // 钉钉服务端已经把语音转写好放在 audio.recognition 里。这跟企业微信
                // 的 voice.content 是一个模式 —— webhook 自带 ASR 文本，0 STT 调用。
                Map<String, Object> audioBody = (Map<String, Object>) payload.get("audio");
                String recognition = audioBody != null ? (String) audioBody.get("recognition") : null;
                if (recognition != null && !recognition.isBlank()) {
                    textContent = recognition.trim();
                    contentParts.add(MessageContentPart.text(textContent));
                }
            } else {
                // 默认 text 消息
                Map<String, Object> msgBody = (Map<String, Object>) payload.get("text");
                textContent = msgBody != null ? (String) msgBody.get("content") : null;
                if (textContent != null && !textContent.isBlank()) {
                    contentParts.add(MessageContentPart.text(textContent.trim()));
                }
            }

            String senderId = (String) payload.get("senderStaffId");
            if (senderId == null) {
                senderId = (String) payload.get("senderId");
            }
            if (senderId == null) {
                log.warn("[dingtalk] No senderId found in webhook payload, ignoring message");
                return;
            }
            String senderNick = (String) payload.get("senderNick");
            String conversationId = (String) payload.get("conversationId");
            String msgId = (String) payload.get("msgId");
            String conversationType = (String) payload.get("conversationType");
            String sessionWebhook = (String) payload.get("sessionWebhook");

            if (contentParts.isEmpty() && (textContent == null || textContent.isBlank())) {
                log.debug("[dingtalk] Empty message content, ignoring");
                return;
            }

            String content = textContent != null ? textContent.trim() : "";

            ChannelMessage message = ChannelMessage.builder()
                    .messageId(msgId)
                    .channelType(CHANNEL_TYPE)
                    .senderId(senderId)
                    .senderName(senderNick)
                    .chatId("1".equals(conversationType) ? null : conversationId)
                    .content(content)
                    .contentType(contentParts.stream().anyMatch(p -> "image".equals(p.getType())) ? "image" : "text")
                    .contentParts(contentParts)
                    .inputMode("audio".equals(msgtype) ? "voice" : "text")
                    .timestamp(LocalDateTime.now())
                    .rawPayload(payload)
                    .build();

            message.setReplyToken(sessionWebhook);
            onMessage(message);

        } catch (Exception e) {
            log.error("[dingtalk] Failed to handle webhook: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendMessage(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot send message");
            return;
        }

        String messageType = getConfigString("message_type", "markdown");

        try {
            String jsonBody;
            // card 模式的文本回退也使用 markdown 格式
            if ("markdown".equals(messageType) || "card".equals(messageType)) {
                jsonBody = objectMapper.writeValueAsString(Map.of(
                        "msgtype", "markdown",
                        "markdown", Map.of(
                                "title", "MateClaw",
                                "text", content
                        )
                ));
            } else {
                jsonBody = objectMapper.writeValueAsString(Map.of(
                        "msgtype", "text",
                        "text", Map.of("content", content)
                ));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk] Send message failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[dingtalk] Message sent successfully via sessionWebhook");
            }

        } catch (Exception e) {
            log.error("[dingtalk] Failed to send message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void sendContentParts(String targetId, List<MessageContentPart> parts) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot send message");
            return;
        }

        // 钉钉 sessionWebhook 只支持 text/markdown/link/actionCard 等类型。
        // 图片需要通过上传 media 后发送，这里暂时将媒体内容以 Markdown 图片语法发出。
        StringBuilder markdown = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null) continue;
            switch (part.getType()) {
                case "text" -> { if (part.getText() != null) markdown.append(part.getText()); }
                case "image" -> {
                    if (part.getFileUrl() != null) {
                        markdown.append("\n![图片](").append(part.getFileUrl()).append(")\n");
                    } else {
                        markdown.append("\n[图片]\n");
                    }
                }
                case "file" -> markdown.append("\n[文件: ").append(part.getFileName() != null ? part.getFileName() : "").append("]\n");
                default -> { if (part.getText() != null) markdown.append(part.getText()); }
            }
        }

        sendMessage(targetId, markdown.toString().trim());
    }

    // ==================== 主动推送 ====================

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    /**
     * 主动推送消息
     * <p>
     * targetId 可以是：
     * - sessionWebhook URL（以 http 开头）：直接通过 Webhook 发送
     * - conversationId：通过 Robot API 的 orgGroupSend / privateSend 发送（需 access_token）
     */
    @Override
    public void proactiveSend(String targetId, String content) {
        if (httpClient == null) {
            log.warn("[dingtalk] Channel not started, cannot proactive send");
            return;
        }

        if (targetId.startsWith("http")) {
            // sessionWebhook 直接发送
            sendMessage(targetId, content);
            return;
        }

        // 通过 Robot API 发送：获取 access_token 后调用 /v1.0/robot/oToMessages/batchSend
        String robotCode = getConfigString("robot_code");
        if (robotCode == null || robotCode.isBlank()) {
            log.warn("[dingtalk] robot_code not configured, falling back to sendMessage");
            sendMessage(targetId, content);
            return;
        }

        try {
            String accessToken = getDingTalkAccessToken();
            if (accessToken == null) {
                log.error("[dingtalk] Failed to obtain access_token for proactive send");
                return;
            }

            String messageType = getConfigString("message_type", "markdown");
            Map<String, Object> msgParam;
            String msgKey;
            if ("markdown".equals(messageType) || "card".equals(messageType)) {
                msgKey = "sampleMarkdown";
                msgParam = Map.of("title", "MateClaw", "text", content);
            } else {
                msgKey = "sampleText";
                msgParam = Map.of("content", content);
            }

            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "robotCode", robotCode,
                    "userIds", List.of(targetId),
                    "msgKey", msgKey,
                    "msgParam", objectMapper.writeValueAsString(msgParam)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend"))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", accessToken)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk] Proactive send failed: status={}, body={}", response.statusCode(), response.body());
            } else {
                log.debug("[dingtalk] Proactive message sent to {}", targetId);
            }
        } catch (Exception e) {
            log.error("[dingtalk] Failed to proactive send: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取钉钉 access_token（用于 Robot API）
     */
    private String getDingTalkAccessToken() {
        // 如果有 AI Card Manager，复用其 token
        if (aiCardManager != null) {
            return aiCardManager.ensureAccessToken();
        }

        String clientId = getConfigString("client_id");
        String clientSecret = getConfigString("client_secret");
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "appKey", clientId,
                    "appSecret", clientSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.dingtalk.com/v1.0/oauth2/accessToken"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
            return (String) result.get("accessToken");
        } catch (Exception e) {
            log.error("[dingtalk] Failed to get access_token: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    // ==================== Stream 断线重连 ====================

    /**
     * Stream 连接断开时由外部调用（或内部检测到断开时调用）
     * <p>
     * 触发指数退避重连：重新初始化 httpClient 和 AI Card Manager
     */
    public void notifyStreamDisconnected(String reason) {
        onDisconnected("Stream disconnected: " + reason);
    }

    @Override
    protected void doReconnect() {
        log.info("[dingtalk] Reconnecting: {} (mode={})", channelEntity.getName(), getConnectionMode());
        // 完整重建：doStop() + doStart()（默认 AbstractChannelAdapter 行为）
        super.doReconnect();
    }
}
