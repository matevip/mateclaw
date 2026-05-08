package vip.mate.workflow.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Payload body addressed by a stable URI. Small payloads (&lt; 256KB) live
 * inline in {@code contentBytes}; larger payloads point at filesystem or
 * object storage via {@code storageKind} + {@code storageRef}. {@code sha256}
 * is for tamper detection only — v0 does not deduplicate across runs.
 */
@Data
@TableName("mate_workflow_payload")
public class WorkflowPayloadEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String payloadUri;

    private Long workspaceId;

    @TableField(value = "content_bytes", updateStrategy = FieldStrategy.ALWAYS)
    private byte[] contentBytes;

    /** Storage flavour: inline / fs / s3 / oss. */
    private String storageKind;

    @TableField(value = "storage_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String storageRef;

    @TableField(value = "content_type", updateStrategy = FieldStrategy.ALWAYS)
    private String contentType;

    @TableField(value = "sha256", updateStrategy = FieldStrategy.ALWAYS)
    private String sha256;

    @TableField(value = "size_bytes", updateStrategy = FieldStrategy.ALWAYS)
    private Long sizeBytes;

    private LocalDateTime createdAt;
}
