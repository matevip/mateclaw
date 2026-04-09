package vip.mate.channel.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.api.Slack;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import com.slack.api.model.event.MessageEvent;
import lombok.extern.slf4j.Slf4j;
import vip.mate.channel.AbstractChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.ExponentialBackoff;
import vip.mate.channel.model.ChannelEntity;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slack 渠道适配器
 * <p>
 * 通过 Socket Mode 接入 Slack，支持：
 * - 频道消息（自动 thread reply，避免刷屏）
 * - DM 私聊
 * - 审批命令识别（复用 ChannelMessageRouter 的审批拦截）
 * - proactiveSend 主动推送
 * <p>
 * configJson 配置项：
 * - bot_token: Slack Bot OAuth Token (xoxb-...)
 * - app_token: Slack App-Level Token (xapp-...) — Socket Mode 必需
 * - signing_secret: Slack Signing Secret（Webhook 模式使用）
 *
 * @author MateClaw Team
 */
@Slf4j
public class SlackChannelAdapter extends AbstractChannelAdapter {

    private Slack slack;
    private App boltApp;
    private SocketModeApp socketModeApp;
    private String botUserId;

    /** 缓存 channel 消息的 thread_ts，确保同一频道对话在同一 thread 中回复 */
    private final ConcurrentHashMap<String, String> threadTsCache = new ConcurrentHashMap<>();

    public SlackChannelAdapter(ChannelEntity channelEntity,
                                ChannelMessageRouter messageRouter,
                                ObjectMapper objectMapper) {
        super(channelEntity, messageRouter, objectMapper);
        this.backoff = new ExponentialBackoff(3000, 60000, 2.0, -1);
    }

    @Override
    public String getChannelType() {
        return "slack";
    }

    @Override
    protected void doStart() {
        String botToken = getConfigString("bot_token");
        String appToken = getConfigString("app_token");

        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("Slack bot_token is required");
        }
        if (appToken == null || appToken.isBlank()) {
            throw new IllegalArgumentException("Slack app_token is required (for Socket Mode)");
        }

        try {
            // 初始化 Bolt App
            AppConfig appConfig = AppConfig.builder()
                    .singleTeamBotToken(botToken)
                    .build();
            boltApp = new App(appConfig);

            // 注册消息事件处理
            boltApp.event(MessageEvent.class, (req, ctx) -> {
                MessageEvent event = req.getEvent();
                processSlackMessage(event, botToken);
                return ctx.ack();
            });

            // 启动 Socket Mode
            slack = Slack.getInstance();
            socketModeApp = new SocketModeApp(appToken, boltApp);
            socketModeApp.startAsync();

            // 获取 bot 自身的 user ID（用于过滤自己的消息）
            try {
                var authResult = slack.methods(botToken).authTest(r -> r);
                if (authResult.isOk()) {
                    botUserId = authResult.getUserId();
                    log.info("[slack] Bot user ID: {}", botUserId);
                }
            } catch (Exception e) {
                log.warn("[slack] Failed to get bot user ID: {}", e.getMessage());
            }

            log.info("[slack] Socket Mode started for channel: {}", channelEntity.getName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Slack adapter: " + e.getMessage(), e);
        }
    }

    @Override
    protected void doStop() {
        try {
            if (socketModeApp != null) {
                socketModeApp.close();
                socketModeApp = null;
            }
        } catch (Exception e) {
            log.warn("[slack] Error stopping Socket Mode: {}", e.getMessage());
        }
        boltApp = null;
        threadTsCache.clear();
    }

    /**
     * 处理 Slack 消息事件
     */
    private void processSlackMessage(MessageEvent event, String botToken) {
        // 忽略 bot 自身的消息
        if (event.getBotId() != null || (botUserId != null && botUserId.equals(event.getUser()))) {
            return;
        }
        // 忽略 message_changed / message_deleted 等子类型
        if (event.getSubtype() != null) {
            return;
        }

        String text = event.getText();
        if (text == null || text.isBlank()) {
            return;
        }

        lastEventTimeMs.set(System.currentTimeMillis());

        // 构建 conversationId
        String channelId = event.getChannel();
        String channelType = event.getChannelType();
        String senderId = event.getUser();
        boolean isDM = "im".equals(channelType);

        String conversationId;
        if (isDM) {
            conversationId = "slack:dm:" + senderId;
        } else {
            conversationId = "slack:" + channelId;
        }

        // 如果是频道消息，记住 thread_ts 用于后续回复到同一 thread
        String threadTs = event.getThreadTs() != null ? event.getThreadTs() : event.getTs();
        if (!isDM) {
            threadTsCache.put(conversationId, threadTs);
        }

        // 清理 bot mention（<@U12345> 格式）
        String cleanedText = text;
        if (botUserId != null) {
            cleanedText = cleanedText.replaceAll("<@" + botUserId + ">", "").trim();
        }
        if (cleanedText.isBlank()) {
            return;
        }

        // 构建统一消息（使用 Builder 模式）
        // bot prefix 过滤和清理由 AbstractChannelAdapter.onMessage() 统一处理
        ChannelMessage message = ChannelMessage.builder()
                .messageId(event.getTs())
                .channelType("slack")
                .senderId(senderId)
                .senderName(senderId)
                .chatId(channelId)
                .content(cleanedText)
                .contentType("text")
                .contentParts(List.of())
                .timestamp(LocalDateTime.now())
                .replyToken(channelId)
                .build();

        // 转发给消息路由
        onMessage(message);
    }

    @Override
    public void sendMessage(String targetId, String content) {
        String botToken = getConfigString("bot_token");
        if (botToken == null || content == null || content.isBlank()) {
            return;
        }

        try {
            String channelId = targetId;

            // 转换 Markdown 为 Slack mrkdwn
            String slackContent = convertToSlackMarkdown(content);

            // 查找 thread_ts 用于 thread reply
            String threadTs = null;
            for (var entry : threadTsCache.entrySet()) {
                if (entry.getKey().contains(channelId)) {
                    threadTs = entry.getValue();
                    break;
                }
            }

            final String finalThreadTs = threadTs;
            ChatPostMessageResponse response = slack.methods(botToken).chatPostMessage(req -> {
                var builder = req.channel(channelId).text(slackContent);
                if (finalThreadTs != null) {
                    builder.threadTs(finalThreadTs);
                }
                return builder;
            });

            if (!response.isOk()) {
                log.warn("[slack] Failed to send message: {}", response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.error("[slack] Error sending message to {}: {}", targetId, e.getMessage());
        }
    }

    @Override
    public boolean supportsProactiveSend() {
        return true;
    }

    @Override
    public void proactiveSend(String targetId, String content) {
        sendMessage(targetId, content);
    }

    /**
     * Webhook 回调处理（备用模式，Socket Mode 优先）
     */
    public Map<String, Object> handleWebhook(Map<String, Object> payload) {
        // URL Verification challenge
        if ("url_verification".equals(payload.get("type"))) {
            return Map.of("challenge", payload.getOrDefault("challenge", ""));
        }
        return Map.of("status", "ok");
    }

    /**
     * 基础 Markdown -> Slack mrkdwn 转换
     */
    private String convertToSlackMarkdown(String markdown) {
        if (markdown == null) return "";
        String result = markdown;
        // **bold** -> *bold*
        result = result.replaceAll("\\*\\*(.+?)\\*\\*", "*$1*");
        // [text](url) -> <url|text>
        result = result.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "<$2|$1>");
        // # Header -> *Header*
        result = result.replaceAll("(?m)^#{1,6}\\s+(.+)$", "*$1*");
        return result;
    }
}
