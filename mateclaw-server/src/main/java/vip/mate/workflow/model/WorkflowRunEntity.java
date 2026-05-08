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
 * Workflow run instance. Run is locked to a specific revision for stability
 * even when later revisions are published. Initial input and final output are
 * stored as payload URIs to avoid bloating the run row.
 */
@Data
@TableName("mate_workflow_run")
public class WorkflowRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workflowId;

    private Long revisionId;

    private Long workspaceId;

    /** State machine value: pending / running / paused / succeeded / failed / cancelled / timed_out. */
    private String state;

    @TableField(value = "triggered_by", updateStrategy = FieldStrategy.ALWAYS)
    private String triggeredBy;

    @TableField(value = "triggered_meta", updateStrategy = FieldStrategy.ALWAYS)
    private String triggeredMeta;

    @TableField(value = "initial_input_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String initialInputRef;

    @TableField(value = "final_output_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String finalOutputRef;

    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    // Hard-delete only (project convention); column kept for schema compat.
    private Integer deleted;
}
