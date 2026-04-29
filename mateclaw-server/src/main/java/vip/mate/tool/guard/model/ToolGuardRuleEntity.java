package vip.mate.tool.guard.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具安全规则实体
 */
@Data
@TableName("mate_tool_guard_rule")
public class ToolGuardRuleEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String ruleId;
    private String name;
    private String description;
    private String toolName;
    private String paramName;
    private String category;
    private String severity;
    private String decision;
    private String pattern;
    private String excludePattern;
    private String remediation;
    private Boolean builtin;
    private Boolean enabled;
    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
