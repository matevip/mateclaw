package vip.mate.wiki.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * User-defined prompt template applied to a knowledge base's raw materials
 * (and, eventually, pages). One template + one source = one
 * {@link WikiTransformationRunEntity}.
 *
 * <p>Template body supports the placeholders {@code {input_text}} and
 * {@code {title}}, replaced by the executor before the LLM call.
 */
@Data
@TableName("mate_wiki_transformation")
public class WikiTransformationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Pinned KB. {@code null} means the template is available to every KB
     * in the workspace.
     */
    private Long kbId;

    private Long workspaceId;

    /** Stable short identifier; unique per {@code kbId}. */
    private String name;

    private String title;

    private String description;

    /** Prompt body with {@code {input_text}} / {@code {title}} placeholders. */
    private String promptTemplate;

    /**
     * When true, the ingestion pipeline fires this template automatically
     * for every raw material that lands in {@code completed} for a matching
     * KB.
     */
    private Boolean applyDefault;

    /** Optional explicit model override; {@code null} = use KB default. */
    private Long modelId;

    private Boolean enabled;

    /**
     * Where the output of a successful run lands.
     * <ul>
     *   <li>{@code none}  — output stays in the run history only (default).</li>
     *   <li>{@code page}  — output is upserted as a synthesis wiki page on the
     *       same KB; subsequent runs against the same source raw material
     *       update the same page rather than spawning duplicates.</li>
     * </ul>
     */
    private String outputTarget;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
