package vip.mate.skill.installer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builtin skill seed service — RFC-044 §4.2.
 *
 * <p>Scans {@code classpath:skills/*\/SKILL.md} on startup, parses each
 * frontmatter, and upserts the row into {@code mate_skill} so the bundled
 * SKILL.md becomes the single source of truth.
 *
 * <p>Replaces (and obsoletes) the per-skill {@code INSERT INTO mate_skill}
 * blocks in {@code data-{locale}.sql}. Those are kept for one release as a
 * compatibility shim — see RFC-044 §4.2 step 3.
 *
 * <p><b>Upsert key:</b> {@code name}. The mate_skill primary key {@code id}
 * is preserved on update, so nothing referencing a skill by id breaks.
 *
 * <p><b>Field merge policy:</b> frontmatter wins where present; if the
 * frontmatter omits a field (e.g. {@code icon}, {@code tags}, {@code author}),
 * the existing DB value is preserved rather than blanked out. New skills
 * (with no DB row yet) get sensible defaults.
 *
 * <p><b>Order:</b> 110 — runs after Flyway and {@link
 * vip.mate.config.DatabaseBootstrapRunner} (Order 1), so SQL seeds load first
 * and this service then overlays the authoritative classpath SKILL.md.
 */
@Slf4j
@Service
@Order(110)
@RequiredArgsConstructor
public class BuiltinSkillSeedService implements ApplicationRunner {

    private static final String SKILL_GLOB = "classpath*:skills/*/SKILL.md";
    private static final String DEFAULT_AUTHOR = "MateClaw";
    private static final String DEFAULT_ICON = "🛠️";
    private static final String DEFAULT_VERSION = "1.0.0";
    private static final String SKILL_TYPE_BUILTIN = "builtin";

    private final SkillMapper skillMapper;
    private final SkillFrontmatterParser frontmatterParser;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            syncBuiltinSkills();
        } catch (Exception e) {
            log.warn("[SkillSeed] Sync failed (table may not exist yet): {}", e.getMessage());
        }
    }

    /** Public so tests and admin endpoints can re-trigger sync. */
    public SyncStats syncBuiltinSkills() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_GLOB);
        } catch (Exception e) {
            log.warn("[SkillSeed] Failed to scan {}: {}", SKILL_GLOB, e.getMessage());
            return new SyncStats(0, 0, 0, 0);
        }

        int inserted = 0, updated = 0, unchanged = 0, skipped = 0;
        for (Resource resource : resources) {
            try {
                String content = readContent(resource);
                SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
                String name = parsed.getName();
                if (name == null || name.isBlank()) {
                    log.warn("[SkillSeed] {}: SKILL.md has no `name` in frontmatter — skipped",
                            resource.getDescription());
                    skipped++;
                    continue;
                }

                SkillEntity existing = skillMapper.selectOne(
                        new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getName, name));

                if (existing == null) {
                    SkillEntity row = buildNew(parsed, content);
                    skillMapper.insert(row);
                    inserted++;
                    log.info("[SkillSeed] inserted '{}' (version={})", name, row.getVersion());
                } else if (mergeIntoExisting(existing, parsed, content)) {
                    skillMapper.updateById(existing);
                    updated++;
                    log.info("[SkillSeed] updated '{}' (version={})", name, existing.getVersion());
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                log.warn("[SkillSeed] Failed to process {}: {}", resource.getDescription(), e.getMessage());
                skipped++;
            }
        }
        log.info("[SkillSeed] Builtin skills: {} inserted, {} updated, {} unchanged, {} skipped",
                inserted, updated, unchanged, skipped);
        return new SyncStats(inserted, updated, unchanged, skipped);
    }

    private String readContent(Resource resource) throws Exception {
        try (InputStream is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Build a brand-new entity for a skill that has no row in mate_skill yet. */
    private SkillEntity buildNew(SkillFrontmatterParser.ParsedSkillMd parsed, String content) {
        SkillEntity row = new SkillEntity();
        row.setName(parsed.getName());
        row.setDescription(nullIfBlank(parsed.getDescription()));
        row.setSkillType(SKILL_TYPE_BUILTIN);
        row.setBuiltin(true);
        row.setEnabled(true);
        row.setSkillContent(content);
        row.setVersion(stringFromFrontmatter(parsed, "version", DEFAULT_VERSION));
        row.setIcon(stringFromFrontmatter(parsed, "icon", DEFAULT_ICON));
        row.setAuthor(stringFromFrontmatter(parsed, "author", DEFAULT_AUTHOR));
        row.setTags(tagsFromFrontmatter(parsed, parsed.getName()));
        row.setConfigJson(buildConfigJson(parsed));
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        row.setDeleted(0);
        return row;
    }

    /**
     * Apply frontmatter onto an existing row. Returns {@code true} if any
     * tracked field changed and the row needs an UPDATE.
     *
     * <p>Frontmatter wins where present. Fields the frontmatter omits are
     * left as-is so we don't blank out values populated elsewhere (UI,
     * legacy SQL seed, manual admin tweaks).
     */
    private boolean mergeIntoExisting(SkillEntity existing,
                                       SkillFrontmatterParser.ParsedSkillMd parsed,
                                       String content) {
        boolean dirty = false;

        String desc = nullIfBlank(parsed.getDescription());
        if (desc != null && !Objects.equals(existing.getDescription(), desc)) {
            existing.setDescription(desc);
            dirty = true;
        }

        String version = stringFromFrontmatter(parsed, "version", null);
        if (version != null && !Objects.equals(existing.getVersion(), version)) {
            existing.setVersion(version);
            dirty = true;
        }

        String icon = stringFromFrontmatter(parsed, "icon", null);
        if (icon != null && !Objects.equals(existing.getIcon(), icon)) {
            existing.setIcon(icon);
            dirty = true;
        }

        String author = stringFromFrontmatter(parsed, "author", null);
        if (author != null && !Objects.equals(existing.getAuthor(), author)) {
            existing.setAuthor(author);
            dirty = true;
        }

        String tags = tagsFromFrontmatter(parsed, null);
        if (tags != null && !Objects.equals(existing.getTags(), tags)) {
            existing.setTags(tags);
            dirty = true;
        }

        String configJson = buildConfigJson(parsed);
        if (!Objects.equals(existing.getConfigJson(), configJson)) {
            existing.setConfigJson(configJson);
            dirty = true;
        }

        if (!Objects.equals(existing.getSkillContent(), content)) {
            existing.setSkillContent(content);
            dirty = true;
        }

        // Re-affirm builtin classification — historic rows occasionally drifted.
        if (!SKILL_TYPE_BUILTIN.equals(existing.getSkillType())) {
            existing.setSkillType(SKILL_TYPE_BUILTIN);
            dirty = true;
        }
        if (!Boolean.TRUE.equals(existing.getBuiltin())) {
            existing.setBuiltin(true);
            dirty = true;
        }

        return dirty;
    }

    @SuppressWarnings("unchecked")
    private String stringFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed,
                                          String key, String fallback) {
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm == null) return fallback;
        Object value = fm.get(key);
        if (value == null) return fallback;
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Build the canonical {@code tags} string. Accepts either a CSV string,
     * a YAML list, or — when the frontmatter is silent — derives a single
     * tag from the supplied default (typically the skill name).
     *
     * <p>Returns {@code null} when nothing usable was supplied; callers use
     * that as "do not touch".
     */
    @SuppressWarnings("unchecked")
    private String tagsFromFrontmatter(SkillFrontmatterParser.ParsedSkillMd parsed, String defaultTag) {
        Map<String, Object> fm = parsed.getFrontmatter();
        if (fm != null) {
            Object raw = fm.get("tags");
            if (raw instanceof List<?> list) {
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    if (item == null) continue;
                    String s = item.toString().trim();
                    if (s.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(s);
                }
                if (sb.length() > 0) return sb.toString();
            } else if (raw instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return defaultTag != null ? defaultTag : null;
    }

    /**
     * Stable {@code config_json} payload. Preserves the historical shape
     * ({@code upstream}, {@code entryFile}) and adds {@code requiredTools}
     * derived from {@code dependencies.tools}.
     */
    private String buildConfigJson(SkillFrontmatterParser.ParsedSkillMd parsed) {
        // LinkedHashMap → stable key ordering → stable diff against existing.
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("upstream", "mateclaw");
        config.put("entryFile", "SKILL.md");

        SkillFrontmatterParser.SkillDependencies deps = parsed.getDependencies();
        if (deps != null && deps.getTools() != null && !deps.getTools().isEmpty()) {
            config.put("requiredTools", deps.getTools());
        }
        if (parsed.getPlatforms() != null && !parsed.getPlatforms().isEmpty()) {
            config.put("platforms", parsed.getPlatforms());
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            // Fall back to legacy shape — never break startup over JSON encoding.
            return "{\"upstream\":\"mateclaw\",\"entryFile\":\"SKILL.md\"}";
        }
    }

    private String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record SyncStats(int inserted, int updated, int unchanged, int skipped) {}
}
