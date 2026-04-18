package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 页面实体（AI 生成的结构化知识页面）
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_page")
public class WikiPageEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** URL 安全标识符，也是 [[link]] 的目标 */
    private String slug;

    /** 页面标题 */
    private String title;

    /** Markdown 内容（包含 [[links]]） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 一段话摘要（用于上下文注入） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    /** 出站链接（JSON 数组，如 ["slug-a","slug-b"]） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String outgoingLinks;

    /** 来源原始材料 ID（JSON 数组） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceRawIds;

    /** 版本号（每次 AI 更新递增） */
    private Integer version;

    /** 最后更新者：ai / manual */
    private String lastUpdatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
