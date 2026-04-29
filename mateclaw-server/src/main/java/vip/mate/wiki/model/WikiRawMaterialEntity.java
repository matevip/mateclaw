package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki 原始材料实体
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_raw_material")
public class WikiRawMaterialEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** 材料标题 */
    private String title;

    /** 来源类型：text / pdf / docx / url / paste */
    private String sourceType;

    /** 原始文件路径（二进制文件） */
    private String sourcePath;

    /** 原始文本内容（文本类型） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String originalContent;

    /** 提取后的文本（PDF/DOCX 等） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String extractedText;

    /** 内容 SHA-256 哈希（用于去重和变更检测） */
    private String contentHash;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 处理状态：pending / processing / completed / failed */
    private String processingStatus;

    /** 上次处理时间 */
    private LocalDateTime lastProcessedAt;

    /** 上次成功处理时的 content_hash，用于重处理时的短路判断 */
    private String lastProcessedHash;

    /** 错误信息 */
    private String errorMessage;

    /**
     * RFC-012 M2 v2 UI：当前处理阶段（null 未开始 / "route" / "phase-b" / "done"）。
     * 供前端决定是否显示进度条以及显示"准备中"还是具体进度。
     */
    private String progressPhase;

    /** RFC-012 M2 v2 UI：本次处理计划的总页数（route 阶段确定后写入）。 */
    private Integer progressTotal;

    /** RFC-012 M2 v2 UI：已完成的页数（每个 phase B 页成功后 +1）。 */
    private Integer progressDone;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
