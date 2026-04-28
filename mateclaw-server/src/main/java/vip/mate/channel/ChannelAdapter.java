package vip.mate.channel;

import vip.mate.workspace.conversation.model.MessageContentPart;

import java.time.Duration;
import java.util.List;

/**
 * 渠道适配器接口
 * <p>
 * 所有 IM 渠道（钉钉、飞书、企业微信等）均需实现此接口。
 * 统一生命周期管理 + 消息收发抽象。
 *
 * @author MateClaw Team
 */
public interface ChannelAdapter {

    // ==================== 生命周期 ====================

    /**
     * 启动渠道（建立长连接、注册 Webhook 等）
     * 启动失败应抛出异常，不影响其他渠道
     */
    void start();

    /**
     * 停止渠道（断开连接、清理资源）
     */
    void stop();

    /**
     * 渠道是否正在运行
     */
    boolean isRunning();

    // ==================== 消息收发 ====================

    /**
     * 处理来自渠道的入站消息
     * <p>
     * 由渠道实现类在收到消息后调用（Webhook 回调 / 长连接推送），
     * 通常内部会调用 {@link ChannelMessageRouter} 路由到 Agent 处理。
     *
     * @param message 渠道消息（已转换为统一格式）
     */
    void onMessage(ChannelMessage message);

    /**
     * 向渠道发送消息（主动推送）
     * <p>
     * 用于 Agent 回复、定时任务结果推送等场景。
     *
     * @param targetId 目标标识（如 openId、chatId、sessionWebhook 等）
     * @param content  消息内容（Markdown 格式，具体渠道可自行渲染）
     */
    void sendMessage(String targetId, String content);

    /**
     * 发送结构化内容（多模态：文本 + 图片 + 文件等）。
     * <p>
     * 默认实现提取纯文本后退化为 sendMessage；各渠道可覆写此方法
     * 调用平台对应的富媒体 API 发送图片、文件等。
     *
     * @param targetId 目标标识
     * @param parts    结构化内容片段
     */
    default void sendContentParts(String targetId, List<MessageContentPart> parts) {
        // 默认退化：提取文本，忽略媒体
        StringBuilder text = new StringBuilder();
        for (MessageContentPart part : parts) {
            if (part == null) continue;
            switch (part.getType()) {
                case "text" -> { if (part.getText() != null) text.append(part.getText()); }
                case "image" -> text.append("[图片]");
                case "file" -> text.append("[文件: ").append(part.getFileName() != null ? part.getFileName() : "").append("]");
                case "audio" -> text.append("[音频]");
                case "video" -> text.append("[视频]");
                default -> { if (part.getText() != null) text.append(part.getText()); }
            }
        }
        sendMessage(targetId, text.toString());
    }

    /**
     * 渲染并发送消息：过滤 thinking/tool_call 标签、按平台限制分割后逐段发送。
     * <p>
     * 默认实现直接调用 sendMessage（不做渲染）；
     * AbstractChannelAdapter 覆写此方法读取 configJson 中的渲染配置。
     *
     * @param targetId 目标标识
     * @param content  原始消息内容
     */
    default void renderAndSend(String targetId, String content) {
        sendMessage(targetId, content);
    }

    // ==================== 主动推送 ====================

    /**
     * 主动发送消息到指定目标（不依赖 Webhook 回调上下文）
     * <p>
     * 与 sendMessage 的区别：sendMessage 通常在 Webhook 回调链路中使用，
     * targetId 来自 replyToken（如钉钉的 sessionWebhook）。
     * proactiveSend 用于无回调上下文的主动推送场景（如定时任务），
     * targetId 为平台的用户/群组/频道标识。
     * <p>
     * 不支持主动推送的渠道（如 Web）默认抛出 UnsupportedOperationException。
     *
     * @param targetId 目标标识（用户ID / 群组ID / 频道ID，因渠道而异）
     * @param content  消息内容（Markdown 格式）
     */
    default void proactiveSend(String targetId, String content) {
        throw new UnsupportedOperationException(getChannelType() + " does not support proactive send");
    }

    /**
     * RFC-063r §2.10: extended overload that accepts a
     * {@link DeliveryOptions} Parameter Object carrying optional hints
     * (thread id, multi-bot account id, future ext fields).
     *
     * <p>Default implementation delegates to {@link #proactiveSend(String, String)},
     * dropping hints — concrete adapters (Slack, Telegram) override this
     * variant to read {@code threadId} and route into the threading API.
     */
    default void proactiveSend(String targetId, String content, DeliveryOptions options) {
        proactiveSend(targetId, content);
    }

    /**
     * 当前渠道是否支持主动推送
     *
     * @return true 表示支持 proactiveSend
     */
    default boolean supportsProactiveSend() {
        return false;
    }

    // ==================== 元信息 ====================

    /**
     * 获取渠道类型标识
     *
     * @return 渠道类型，如 "web", "dingtalk", "feishu", "telegram"
     */
    String getChannelType();

    /**
     * 获取渠道显示名称
     */
    default String getDisplayName() {
        return getChannelType();
    }

    /**
     * RFC-024 Change 2：本 adapter 认为"多久没活动就视作 stale 需要重启"的阈值。
     *
     * <p>通用默认 60 分钟；长轮询类渠道（如 iLink 微信）应覆盖为 5 分钟，
     * 这样代理/NAT 侧 2–5 分钟 idle-close 把连接切断后，
     * {@code ChannelHealthMonitor} 能在几分钟内触发自动重启，而非静默等整小时。</p>
     */
    default Duration stalenessThreshold() {
        return Duration.ofMinutes(60);
    }
}
