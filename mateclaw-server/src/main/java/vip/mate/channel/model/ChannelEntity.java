package vip.mate.channel.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道实体
 * 渠道实体：支持多种 IM 渠道接入
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_channel")
public class ChannelEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 渠道名称 */
    private String name;

    /** 渠道类型：web / dingtalk / feishu / wechat / discord / qq */
    private String channelType;

    /** 关联的 Agent ID */
    private Long agentId;

    /** Bot 前缀（触发关键词） */
    private String botPrefix;

    /** 渠道配置（JSON，存储 Token/AppId 等） */
    @TableField(value = "config_json", updateStrategy = FieldStrategy.ALWAYS)
    private String configJson;

    /** 是否启用 */
    private Boolean enabled;

    /** 渠道描述 */
    private String description;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
