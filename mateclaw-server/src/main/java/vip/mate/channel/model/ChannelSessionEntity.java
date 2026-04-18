package vip.mate.channel.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道会话存储实体
 * <p>
 * 缓存各渠道的会话标识映射，用于主动推送场景。
 * key 为 conversationId（如 dingtalk:sw:xxx），
 * value 为平台推送所需的标识（sessionWebhook / chat_id / channel_id）。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_channel_session")
public class ChannelSessionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 会话ID（格式：{channelType}:{identifier}） */
    private String conversationId;

    /** 渠道类型 */
    private String channelType;

    /**
     * 推送目标标识
     * <p>
     * 不同渠道含义不同：
     * - 钉钉：sessionWebhook URL 或 userId
     * - 飞书：chat_id（oc_xxx）或 open_id（ou_xxx）
     * - Telegram：chat_id
     * - Discord：channel_id
     * - 企业微信：userId
     */
    private String targetId;

    /** 发送者ID */
    private String senderId;

    /** 发送者名称 */
    private String senderName;

    /** 关联的渠道配置ID */
    private Long channelId;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
