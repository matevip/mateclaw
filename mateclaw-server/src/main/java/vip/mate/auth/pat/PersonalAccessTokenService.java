package vip.mate.auth.pat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * RFC-03 Lane I1 — Personal Access Token service.
 *
 * <p>Headless / CI / SDK callers can authenticate without the interactive
 * JWT login flow. Token plaintext is shown to the user exactly once at
 * creation time; the database only stores SHA-256 hashes, so a DB
 * compromise can't be used to authenticate as anyone.
 *
 * <p>Token format: {@code mc_<43 url-safe base64 chars>} = ~32 bytes of
 * entropy from {@link SecureRandom}. The {@code mc_} prefix lets the
 * auth filter distinguish PAT tokens from JWT tokens by inspection
 * (JWTs always start with {@code eyJ}).
 *
 * <p>{@link #findActiveByPlaintext} is the hot path called on every
 * authenticated PAT request — kept to a single indexed lookup with no
 * JOINs. {@link #recordUse} debounces last-used updates to once per
 * minute per token to avoid hammering the row on busy CI loops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAccessTokenService {

    /** RFC-03 Lane I1 — observable prefix for PAT plaintext. */
    public static final String PAT_PREFIX = "mc_";

    /** Bytes of entropy in each freshly generated token. 32 bytes = 256 bits. */
    private static final int TOKEN_BYTES = 32;

    /** Throttle window for last-used writes. Loops at >1Hz still get a
     *  freshness signal, but we don't write on every single call. */
    private static final long LAST_USED_DEBOUNCE_SECONDS = 60;

    private final PersonalAccessTokenMapper mapper;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Mint a new PAT for {@code userId}. Returns the plaintext exactly once
     * — there is no way to recover it later from the database.
     *
     * @param userId    owner id (joined to mate_user.id)
     * @param name      human-readable label, may be null
     * @param scopes    comma-separated scope list, null = "*"
     * @param expiresAt optional hard expiry, null = never expires
     * @return the plaintext token; the caller is responsible for surfacing
     *         it to the user and never persisting it server-side
     */
    public CreatedToken create(Long userId, String name, String scopes, LocalDateTime expiresAt) {
        if (userId == null) {
            throw new MateClawException("err.auth.pat_user_required",
                    "PAT requires an owning user");
        }
        String plaintext = generatePlaintext();
        String hash = sha256Hex(plaintext);

        PersonalAccessTokenEntity entity = new PersonalAccessTokenEntity();
        entity.setUserId(userId);
        entity.setName(name);
        entity.setTokenHash(hash);
        entity.setScopes(scopes);
        entity.setExpiresAt(expiresAt);
        entity.setEnabled(true);
        entity.setDeleted(0);
        mapper.insert(entity);

        log.info("[PAT] Created token id={} userId={} name={} expiresAt={}",
                entity.getId(), userId, name, expiresAt);
        return new CreatedToken(entity.getId(), plaintext, entity);
    }

    /**
     * Auth-filter hot path — find an enabled, unexpired token whose
     * {@link PersonalAccessTokenEntity#getTokenHash()} matches the SHA-256
     * of {@code plaintext}.
     *
     * <p>Returns empty for null / blank input, missing prefix, hash miss,
     * disabled flag, or past-expiry — auth filter doesn't need to
     * distinguish, it just rejects.
     */
    public Optional<PersonalAccessTokenEntity> findActiveByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Optional.empty();
        if (!plaintext.startsWith(PAT_PREFIX)) return Optional.empty();
        String hash = sha256Hex(plaintext);

        PersonalAccessTokenEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<PersonalAccessTokenEntity>()
                        .eq(PersonalAccessTokenEntity::getTokenHash, hash)
                        .eq(PersonalAccessTokenEntity::getEnabled, true)
                        .eq(PersonalAccessTokenEntity::getDeleted, 0)
                        .last("LIMIT 1"));
        if (entity == null) return Optional.empty();
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        return Optional.of(entity);
    }

    /**
     * Record last-used timestamp on the token. Debounced so a CI loop at
     * 5Hz doesn't write 5x per second; the last-used field is observability,
     * not a correctness gate.
     */
    public void recordUse(PersonalAccessTokenEntity entity) {
        if (entity == null || entity.getId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (!shouldRecordUse(entity.getLastUsedAt(), now)) return;
        try {
            PersonalAccessTokenEntity update = new PersonalAccessTokenEntity();
            update.setId(entity.getId());
            update.setLastUsedAt(now);
            mapper.updateById(update);
            entity.setLastUsedAt(now);
        } catch (Exception e) {
            // Best-effort — never fail an authenticated request because we
            // can't write a metadata column.
            log.debug("[PAT] last_used_at write failed for token {}: {}",
                    entity.getId(), e.getMessage());
        }
    }

    /**
     * Pure debounce predicate — package-private for unit testing without
     * the MyBatis-Plus stack. Returns true when the call should write,
     * false when it falls inside the {@link #LAST_USED_DEBOUNCE_SECONDS}
     * window of the previous write.
     */
    static boolean shouldRecordUse(LocalDateTime lastUsedAt, LocalDateTime now) {
        if (lastUsedAt == null) return true;
        return !lastUsedAt.plusSeconds(LAST_USED_DEBOUNCE_SECONDS).isAfter(now);
    }

    /**
     * List tokens for {@code userId}, ordered most-recently-created first.
     * Plaintext is never returned — only metadata.
     */
    public List<PersonalAccessTokenEntity> listByUser(Long userId) {
        if (userId == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<PersonalAccessTokenEntity>()
                .eq(PersonalAccessTokenEntity::getUserId, userId)
                .eq(PersonalAccessTokenEntity::getDeleted, 0)
                .orderByDesc(PersonalAccessTokenEntity::getCreateTime));
    }

    /**
     * Soft-revoke. Two-step so the owner check is explicit and we don't
     * rely on a single composite UPDATE for both the WHERE-userId guard
     * and the SET — easier to test, and protects against a future
     * refactor accidentally dropping the user_id eq.
     *
     * <p>Throws {@code err.auth.pat_not_found} for both "token doesn't exist"
     * and "token belongs to another user" — the unified error code keeps
     * us from leaking which token ids exist across user boundaries.
     */
    public void revoke(Long tokenId, Long ownerUserId) {
        if (tokenId == null || ownerUserId == null) return;
        PersonalAccessTokenEntity existing = mapper.selectById(tokenId);
        if (existing == null
                || existing.getDeleted() != null && existing.getDeleted() == 1
                || !ownerUserId.equals(existing.getUserId())) {
            throw new MateClawException("err.auth.pat_not_found",
                    "PAT not found or not owned by current user: " + tokenId);
        }
        existing.setEnabled(false);
        existing.setDeleted(1);
        mapper.updateById(existing);
        log.info("[PAT] Revoked token id={} ownerUserId={}", tokenId, ownerUserId);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Package-private so unit tests can compute the hash without going
     *  through the service. */
    static String sha256Hex(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Generate a fresh PAT plaintext. Format: {@value PAT_PREFIX} +
     * URL-safe base64 of {@value TOKEN_BYTES} bytes from {@link SecureRandom}.
     * Package-private so tests can verify the prefix + length without
     * minting a real token through the public API.
     */
    String generatePlaintext() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return PAT_PREFIX + body;
    }

    /** Return value of {@link #create} — entity (sans plaintext) plus the
     *  one-shot plaintext the user must save now. */
    public record CreatedToken(Long id, String plaintext, PersonalAccessTokenEntity entity) {}
}
