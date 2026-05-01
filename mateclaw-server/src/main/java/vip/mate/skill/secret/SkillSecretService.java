package vip.mate.skill.secret;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.skill.repository.SkillSecretMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RFC-091 settings bridge — read/write/list AES-encrypted secret values
 * tied to a skill.
 *
 * <p>Storage: {@code mate_skill_secret} (one row per skill_id + key).
 * Encryption uses Hutool AES with a 16-byte key derived from the
 * configured master key; the same property already encrypts datasource
 * passwords ({@code mateclaw.datasource.encrypt-key}) so operators only
 * have one secret to rotate.
 *
 * <p>Logging policy: never log raw values. Any code path that touches
 * decrypted plaintext goes through {@link #mask} first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSecretService {

    /** Allow letters, digits, and underscore — env-var-shaped keys only. */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private final SkillSecretMapper skillSecretMapper;

    /**
     * AES key. Reused with the datasource password key so a single
     * {@code MATECLAW_ENCRYPT_KEY} env var rotates everything that
     * stores secrets at rest.
     */
    @Value("${mateclaw.datasource.encrypt-key:MateClaw@2024Key!}")
    private String encryptKey;

    // ==================== read ====================

    /**
     * Decrypt every active secret for a skill. Returns an insertion-order
     * map (LinkedHashMap) so callers writing it into a process's
     * environment get deterministic ordering.
     */
    public Map<String, String> getDecrypted(Long skillId) {
        if (skillId == null) return Collections.emptyMap();
        List<SkillSecretEntity> rows = skillSecretMapper.selectList(
                new LambdaQueryWrapper<SkillSecretEntity>()
                        .eq(SkillSecretEntity::getSkillId, skillId)
                        .orderByAsc(SkillSecretEntity::getSecretKey));
        Map<String, String> out = new LinkedHashMap<>();
        AES aes = aes();
        for (SkillSecretEntity row : rows) {
            try {
                out.put(row.getSecretKey(), aes.decryptStr(row.getEncryptedValue()));
            } catch (Exception e) {
                // Defensive: a stale ciphertext from a rotated key shouldn't
                // crash the runtime. Log key (not value) and skip — the user
                // can re-set the secret via the UI.
                log.warn("Failed to decrypt skill secret skill_id={} key={}: {}",
                        skillId, row.getSecretKey(), e.getMessage());
            }
        }
        return out;
    }

    /** Keys + masked previews for the UI; never returns plaintext values. */
    public List<SecretSummary> listSummaries(Long skillId) {
        if (skillId == null) return List.of();
        List<SkillSecretEntity> rows = skillSecretMapper.selectList(
                new LambdaQueryWrapper<SkillSecretEntity>()
                        .eq(SkillSecretEntity::getSkillId, skillId)
                        .orderByAsc(SkillSecretEntity::getSecretKey));
        AES aes = aes();
        return rows.stream().map(row -> {
            String preview;
            try {
                preview = mask(aes.decryptStr(row.getEncryptedValue()));
            } catch (Exception e) {
                preview = "<decryption failed>";
            }
            return new SecretSummary(row.getSecretKey(), preview, row.getUpdateTime());
        }).collect(Collectors.toList());
    }

    // ==================== write ====================

    /** Upsert a secret. Empty/null plaintext deletes the row instead. */
    public void put(Long skillId, String key, String plaintext) {
        validate(skillId, key);
        if (plaintext == null || plaintext.isEmpty()) {
            remove(skillId, key);
            return;
        }
        String encrypted = aes().encryptHex(plaintext);
        SkillSecretEntity existing = skillSecretMapper.selectOne(
                new LambdaQueryWrapper<SkillSecretEntity>()
                        .eq(SkillSecretEntity::getSkillId, skillId)
                        .eq(SkillSecretEntity::getSecretKey, key));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            SkillSecretEntity row = new SkillSecretEntity();
            row.setSkillId(skillId);
            row.setSecretKey(key);
            row.setEncryptedValue(encrypted);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            row.setDeleted(0);
            skillSecretMapper.insert(row);
        } else {
            existing.setEncryptedValue(encrypted);
            existing.setUpdateTime(now);
            existing.setDeleted(0);
            skillSecretMapper.updateById(existing);
        }
        log.info("Skill secret upserted skill_id={} key={} (value redacted)", skillId, key);
    }

    /** Soft-delete via the BaseMapper, preserving audit history. */
    public void remove(Long skillId, String key) {
        validate(skillId, key);
        skillSecretMapper.delete(new LambdaQueryWrapper<SkillSecretEntity>()
                .eq(SkillSecretEntity::getSkillId, skillId)
                .eq(SkillSecretEntity::getSecretKey, key));
        log.info("Skill secret removed skill_id={} key={}", skillId, key);
    }

    /** Cascade-delete all secrets for a skill — called when the skill row
     *  itself is hard-deleted. */
    public int purgeForSkill(Long skillId) {
        if (skillId == null) return 0;
        return skillSecretMapper.hardDeleteBySkillId(skillId);
    }

    // ==================== helpers ====================

    private void validate(Long skillId, String key) {
        if (skillId == null) {
            throw new MateClawException("err.skill_secret.skill_id_required",
                    "skillId must be provided");
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new MateClawException("err.skill_secret.invalid_key",
                    "secret key must match [A-Za-z_][A-Za-z0-9_]{0,127}: " + key);
        }
    }

    /** Shared AES instance is cheap to build; keeping it stateless avoids
     *  any thread-visibility worries with the cached key bytes. */
    private AES aes() {
        byte[] keyBytes = Arrays.copyOf(encryptKey.getBytes(StandardCharsets.UTF_8), 16);
        return SecureUtil.aes(keyBytes);
    }

    /**
     * Mask a value for UI display. {@code <=4 chars → "••••"};
     * {@code >4 → first 2 + dots + last 2} so users can recognize a key
     * without it being copy-pasteable.
     */
    static String mask(String plaintext) {
        if (plaintext == null) return "";
        int len = plaintext.length();
        if (len <= 4) return "••••";
        return plaintext.substring(0, 2) + "••••" + plaintext.substring(len - 2);
    }

    /** UI-friendly view: key + preview, never the plaintext value. */
    public record SecretSummary(String key, String preview, LocalDateTime updatedAt) {}
}
