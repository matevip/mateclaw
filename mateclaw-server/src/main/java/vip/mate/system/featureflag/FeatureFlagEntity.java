package vip.mate.system.featureflag;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row in the runtime feature-flag store.
 *
 * <p>Backed by {@code mate_feature_flag}. Read/write paths go through
 * {@link FeatureFlagService}; no service should query the table directly.
 *
 * @author MateClaw Team
 */
@Data
@TableName("mate_feature_flag")
public class FeatureFlagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Stable identifier; convention is {@code <module>.<capability>.enabled}. */
    private String flagKey;

    /** Master switch. When false, all evaluations short-circuit to false. */
    private Boolean enabled;

    private String description;

    /** Comma-separated KB ids; null/blank = applies to all KBs. */
    private String whitelistKbIds;

    /** Comma-separated user ids; null/blank = applies to all users. */
    private String whitelistUserIds;

    /** Percentage rollout (0..100). Only consulted when both whitelists are blank. */
    private Integer rolloutPercent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
