package vip.mate.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-step execution row. {@code stepIndex} is the zero-based index in the
 * revision's steps array; {@code iterationIndex} is reserved for fan_out
 * iterations (and future loop bodies).
 */
@Data
@TableName("mate_workflow_run_step")
public class WorkflowRunStepEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Integer stepIndex;

    @TableField(value = "iteration_index", updateStrategy = FieldStrategy.ALWAYS)
    private Integer iterationIndex;

    @TableField(value = "step_name", updateStrategy = FieldStrategy.ALWAYS)
    private String stepName;

    @TableField(value = "agent_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long agentId;

    private String state;

    @TableField(value = "input_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String inputRef;

    @TableField(value = "output_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String outputRef;

    @TableField(value = "output_summary", updateStrategy = FieldStrategy.ALWAYS)
    private String outputSummary;

    @TableField(value = "output_content_type", updateStrategy = FieldStrategy.ALWAYS)
    private String outputContentType;

    @TableField(value = "error_message", updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    @TableField(value = "duration_ms", updateStrategy = FieldStrategy.ALWAYS)
    private Long durationMs;

    @TableField(value = "token_input", updateStrategy = FieldStrategy.ALWAYS)
    private Integer tokenInput;

    @TableField(value = "token_output", updateStrategy = FieldStrategy.ALWAYS)
    private Integer tokenOutput;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;
}
