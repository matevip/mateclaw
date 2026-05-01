package vip.mate.skill.secret;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFC-091 settings bridge — one row per (skill_id, secret_key).
 *
 * <p>The {@link #encryptedValue} column is AES-encrypted at rest (see
 * {@link SkillSecretService}). Logical-deletes via {@link #deleted}
 * mirror the rest of the schema; the runtime never reads soft-deleted
 * rows so users can revoke a secret by setting {@code deleted=1}
 * without losing audit history.
 */
@Data
@TableName("mate_skill_secret")
public class SkillSecretEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long skillId;

    /** Env-var-shaped key, e.g. {@code AIRTABLE_API_KEY}. */
    private String secretKey;

    /** AES-encrypted value; never logged. */
    private String encryptedValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
