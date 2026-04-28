package vip.mate.approval.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具审批记录实体
 */
@Data
@TableName("mate_tool_approval")
public class ToolApprovalEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pendingId;
    private String conversationId;
    private String userId;
    private String agentId;
    private String channelType;
    private String requesterName;
    private String replyTarget;
    private String toolName;
    private String toolArguments;
    private String toolCallPayload;
    private String toolCallHash;
    private String siblingToolCalls;
    private String summary;
    private String findingsJson;
    private String maxSeverity;
    private String status;
    private String resolvedBy;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime expireAt;

    /**
     * RFC-063r §2.12: serialized {@link vip.mate.agent.context.ChatOrigin}
     * snapshot captured when this approval was created. The Memento lets
     * ChannelMessageRouter.replayApprovedToolCall (and the web ApprovalController
     * replay path) restore the originating channel/workspace context after
     * a process restart, so a tool approved hours later still binds back to
     * the original channel.
     */
    private String chatOrigin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
