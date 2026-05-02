package vip.mate.auth.pat;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFC-03 Lane I1 — Personal Access Token entity.
 *
 * <p>Plaintext tokens are never persisted; only the SHA-256 hash lives in
 * {@link #tokenHash}. This way a DB compromise reveals ownership and
 * scope but not the secret needed to actually authenticate. The user
 * sees the plaintext exactly once at creation time.
 */
@Data
@TableName("mate_personal_access_token")
public class PersonalAccessTokenEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Owner — joined to mate_user.id; one user may own many tokens. */
    private Long userId;

    /** Human-readable label so the owner can tell tokens apart in the UI. */
    private String name;

    /** SHA-256 hex of the plaintext, lowercase. UNIQUE indexed for O(1) auth lookups. */
    private String tokenHash;

    /**
     * Comma-separated scope tokens (e.g. {@code "chat:read,chat:write"}).
     * The first version ships with implicit {@code "*"} scope when null,
     * matching the user's JWT-equivalent permissions; finer-grained scope
     * checking lands in a follow-up RFC.
     */
    private String scopes;

    /** Updated on each successful auth — debounced to once per minute by the service. */
    private LocalDateTime lastUsedAt;

    /** Optional hard expiry. Null = never expires (until manually revoked). */
    private LocalDateTime expiresAt;

    /** Soft revoke — auth filter rejects disabled tokens immediately. */
    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
