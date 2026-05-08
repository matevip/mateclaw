package vip.mate.trigger.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trigger row. {@code patternVersion} is a lamport counter that fire callbacks
 * compare against the row on every fire; mismatches mean another instance has
 * updated the cron expression and the local schedule must self-cancel.
 */
@Data
@TableName("mate_trigger")
public class TriggerEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    @TableField(value = "name", updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    /** Pattern flavour: cron / webhook / channel_message / agent_lifecycle / content_match / workflow_completion. */
    private String patternType;

    @TableField(value = "pattern_json", updateStrategy = FieldStrategy.ALWAYS)
    private String patternJson;

    /** Routing target type: agent or workflow. */
    private String targetType;

    private Long targetId;

    @TableField(value = "payload_template", updateStrategy = FieldStrategy.ALWAYS)
    private String payloadTemplate;

    private Integer rateLimitPerMin;

    private Integer dedupWindowSecs;

    private Boolean botSelfFilter;

    private Boolean enabled;

    private Long fireCount;

    private Long maxFires;

    @TableField(value = "last_fired_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastFiredAt;

    /** Most recent dispatch outcome message; null on success, populated on
     *  SKIPPED / FAILED so the UI can show why a trigger has stopped firing. */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    /** Stamp of the last dispatch attempt regardless of outcome — used to
     *  distinguish "never attempted" from "attempted but skipped". */
    @TableField(value = "last_dispatched_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime lastDispatchedAt;

    /** Lamport counter — bump on every cron expression / payload template change. */
    private Long patternVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // Hard-delete only (project convention); column kept for schema compat.
    private Integer deleted;
}
