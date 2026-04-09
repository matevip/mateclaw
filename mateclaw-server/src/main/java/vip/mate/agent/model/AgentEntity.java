package vip.mate.agent.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 配置实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_agent")
public class AgentEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Agent 名称 */
    private String name;

    /** Agent 描述 */
    private String description;

    /** Agent 类型：react / plan_execute */
    private String agentType;

    /** 系统提示词 */
    @TableField(value = "system_prompt", updateStrategy = FieldStrategy.ALWAYS)
    private String systemPrompt;

    /**
     * 保留但不再生效：运行时统一使用全局默认模型（ModelConfigService.getDefaultModel()）。
     * 该字段为历史残留，仅保留以避免数据库迁移。
     */
    @Deprecated
    private String modelName;

    /** 最大迭代次数 */
    private Integer maxIterations;

    /** 是否启用 */
    private Boolean enabled;

    /** 图标（emoji 或 URL） */
    private String icon;

    /** 标签（逗号分隔） */
    private String tags;

    /** 所属工作区 ID（默认 1 = default） */
    private Long workspaceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
