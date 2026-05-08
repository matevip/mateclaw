package vip.mate.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable workflow pause row. Holds the resume token and links back to the
 * external approval row (or other callback source) so that resume can be
 * triggered idempotently after a JVM restart.
 */
@Data
@TableName("mate_workflow_run_pause")
public class WorkflowRunPauseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    private Long stepId;

    /** Source of the pause: await_approval, external_callback, etc. */
    private String pauseKind;

    /** Random server-generated token used as the resume entry key. */
    private String pauseToken;

    @TableField(value = "external_approval_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long externalApprovalId;

    private LocalDateTime pausedAt;

    @TableField(value = "resume_deadline", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resumeDeadline;

    @TableField(value = "resume_payload_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String resumePayloadRef;

    @TableField(value = "resumed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime resumedAt;

    /** Outcome on resume: approved / rejected / timeout / cancelled. */
    @TableField(value = "resume_outcome", updateStrategy = FieldStrategy.ALWAYS)
    private String resumeOutcome;
}
