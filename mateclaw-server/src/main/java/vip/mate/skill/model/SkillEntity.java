package vip.mate.skill.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能实体
 * 技能实体：可扩展的功能模块
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_skill")
public class SkillEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 技能名称 */
    private String name;

    /** 技能描述 */
    private String description;

    /** 技能类型：builtin（内置）/ custom（自定义）/ mcp（MCP协议） */
    private String skillType;

    /** 技能图标（emoji 或 URL） */
    private String icon;

    /** 技能版本 */
    private String version;

    /** 技能作者 */
    private String author;

    /** 技能配置（JSON） */
    @TableField(value = "config_json", updateStrategy = FieldStrategy.ALWAYS)
    private String configJson;

    /** 技能代码/脚本内容（旧字段，保留兼容） */
    @TableField(value = "source_code", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceCode;

    /**
     * SKILL.md 完整内容 — 技能执行协议
     * <p>
     * 采用 SKILL.md 格式：YAML frontmatter + Markdown 正文。
     * Agent 通过阅读此内容理解技能的用途、执行方式和注意事项。
     * <p>
     * 格式示例:
     * <pre>
     * ---
     * name: pdf
     * description: PDF 处理技能
     * metadata: { "builtin_skill_version": "1.0" }
     * ---
     * # PDF Processing Guide
     * ## Prerequisites
     * ...（详细使用说明）
     * </pre>
     */
    @TableField(value = "skill_content", updateStrategy = FieldStrategy.ALWAYS)
    private String skillContent;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否系统内置（不可删除） */
    private Boolean builtin;

    /** 标签（逗号分隔） */
    private String tags;

    /** RFC-023：来源对话 ID（Agent 自治合成时记录） */
    private String sourceConversationId;

    /**
     * RFC-023：安全扫描状态。
     * NULL = 旧数据或手动创建（不受扫描约束），PASSED = 扫描通过，FAILED = 扫描拦截。
     * listEnabledSkills 过滤条件：NULL 或 PASSED 才加载。
     */
    private String securityScanStatus;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
