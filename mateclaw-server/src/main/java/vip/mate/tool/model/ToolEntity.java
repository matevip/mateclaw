package vip.mate.tool.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具实体
 * 工具实体：Agent 可调用的原子能力
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_tool")
public class ToolEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 工具名称（唯一标识） */
    private String name;

    /** 工具显示名称 */
    private String displayName;

    /** 工具描述（用于 LLM 理解） */
    private String description;

    /** 工具类型：builtin（内置Java工具）/ mcp（MCP协议） */
    private String toolType;

    /** Spring Bean 名称（用于 ToolRegistry 与 DB 的映射，builtin 工具必填） */
    private String beanName;

    /** 工具图标 */
    private String icon;

    /** MCP 服务器地址（toolType=mcp 时使用） */
    private String mcpEndpoint;

    /** 工具参数 Schema（JSON Schema 格式） */
    @TableField(value = "params_schema", updateStrategy = FieldStrategy.ALWAYS)
    private String paramsSchema;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否系统内置 */
    private Boolean builtin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
