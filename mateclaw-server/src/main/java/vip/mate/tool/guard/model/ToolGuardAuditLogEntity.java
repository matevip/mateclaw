package vip.mate.tool.guard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具安全审计日志实体
 */
@Data
@TableName("mate_tool_guard_audit_log")
public class ToolGuardAuditLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String conversationId;
    private String agentId;
    private String userId;
    private String channelType;
    private String toolName;
    private String toolParamsJson;
    private String decision;
    private String maxSeverity;
    private String findingsJson;
    private String pendingId;
    private String replayPayloadHash;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
