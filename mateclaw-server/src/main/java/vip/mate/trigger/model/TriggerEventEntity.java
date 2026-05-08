package vip.mate.trigger.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trigger dedup-window row. {@code dedupKey} carries envelope.eventId, falling
 * back to a content sha256 when the upstream channel did not provide a stable
 * id. {@code expiresAt} is set on insert to {@code receivedAt + dedupWindowSecs}
 * so the cleanup task can sweep expired rows.
 */
@Data
@TableName("mate_trigger_event")
public class TriggerEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long triggerId;

    private String dedupKey;

    /** Filled by DB DEFAULT CURRENT_TIMESTAMP when left null on insert. */
    private LocalDateTime receivedAt;

    private LocalDateTime expiresAt;
}
