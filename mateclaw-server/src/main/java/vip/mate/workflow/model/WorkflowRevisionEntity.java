package vip.mate.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Immutable published snapshot of a workflow. The {@code revision} column is
 * monotonic per workflow; rows are append-only after publish.
 */
@Data
@TableName("mate_workflow_revision")
public class WorkflowRevisionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private Integer revision;

    @TableField(value = "graph_json", updateStrategy = FieldStrategy.ALWAYS)
    private String graphJson;

    private String schemaVersion;

    @TableField(value = "published_note", updateStrategy = FieldStrategy.ALWAYS)
    private String publishedNote;

    @TableField(value = "published_by", updateStrategy = FieldStrategy.ALWAYS)
    private Long publishedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
