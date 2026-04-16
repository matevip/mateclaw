package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wiki chunk 实体
 * <p>
 * RFC-013 最小切片：持久化 splitIntoChunks() 的产物，为后续 embedding (RFC-011)、
 * citation、chunk 级增量处理提供基础。
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_wiki_chunk")
public class WikiChunkEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 ID */
    private Long kbId;

    /** 来源原始材料 ID */
    private Long rawId;

    /** chunk 在材料内的序号（0-based） */
    private Integer ordinal;

    /** chunk 文本内容 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;

    /** 字符数 */
    private Integer charCount;

    /** 在原始文本中的起始偏移 */
    private Integer startOffset;

    /** 在原始文本中的结束偏移 */
    private Integer endOffset;

    /** 内容 SHA-256 哈希（增量处理依据） */
    private String contentHash;

    /** RFC-011：向量 embedding（float32[] little-endian 序列化） */
    private byte[] embedding;

    /** RFC-011：生成该 embedding 的模型名称（切模型时需全量重嵌） */
    private String embeddingModel;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
