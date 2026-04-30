package vip.mate.skill.runtime;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 技能包解析器
 * 将 SkillEntity 解析为 ResolvedSkill（运行时可用的技能包）
 * <p>
 * 三级解析流程：
 * <ol>
 *   <li>显式 skillDir（configJson.skillDir）→ source="directory"</li>
 *   <li>约定路径（{workspace-root}/{skillName}/）→ source="convention"</li>
 *   <li>数据库 skillContent → source="database"</li>
 * </ol>
 * 解析后依次执行：安全扫描 → 依赖检查
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillPackageResolver {

    private final SkillFrontmatterParser frontmatterParser;
    private final SkillDirectoryScanner directoryScanner;
    private final SkillSecurityService securityService;
    private final SkillDependencyChecker dependencyChecker;
    private final ObjectMapper objectMapper;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillMapper skillMapper;

    /**
     * 解析技能实体为运行时技能包（完整流程）
     */
    public ResolvedSkill resolve(SkillEntity entity) {
        String configuredDir = extractSkillDirString(entity);
        Path skillDir = configuredDir != null ? Paths.get(configuredDir) : null;

        ResolvedSkill resolved;

        // 三级解析：explicit → convention → database
        if (skillDir != null && Files.exists(skillDir) && Files.isDirectory(skillDir)) {
            // 1. 显式配置的 skillDir
            resolved = resolveFromDirectory(entity, skillDir, configuredDir, "directory");
        } else {
            // 2. 约定路径 {workspace-root}/{skillName}/
            Path conventionPath = workspaceManager.resolveConventionPath(entity.getName());
            if (Files.exists(conventionPath) && Files.isDirectory(conventionPath)) {
                resolved = resolveFromDirectory(entity, conventionPath, conventionPath.toString(), "convention");
            } else {
                // 3. 数据库 skillContent
                resolved = resolveFromDatabase(entity, configuredDir);
            }
        }

        // 2. 安全扫描
        applySecurity(resolved);

        // 3. 依赖检查
        applyDependencyCheck(resolved);

        // 4. 综合判定 runtimeAvailable
        resolveRuntimeAvailability(resolved);

        // 5. RFC-042 §2.3 — persist the scan outcome so the admin UI can show
        //    findings after a restart (previously they lived only in memory).
        persistScanOutcome(entity, resolved);

        return resolved;
    }

    /**
     * Write back the latest scan status / findings JSON / timestamp when
     * they differ from what's already on the row. Keeps the DB in sync
     * without re-writing on every idempotent refresh.
     *
     * <p>Diff-based so a fresh resolve loop across N enabled skills is
     * effectively free when nothing has changed on disk. Errors here are
     * non-fatal — the scan result is already attached to {@code resolved},
     * so the UI will still see it for this request.
     */
    private void persistScanOutcome(SkillEntity entity, ResolvedSkill resolved) {
        if (entity == null || entity.getId() == null) return;

        String newStatus = deriveScanStatus(resolved);
        String newJson = serializeFindings(resolved.getSecurityFindings());
        boolean statusChanged = !Objects.equals(entity.getSecurityScanStatus(), newStatus);
        boolean findingsChanged = !Objects.equals(entity.getSecurityScanResult(), newJson);

        if (!statusChanged && !findingsChanged) {
            return;
        }

        try {
            // Whitelist via LambdaUpdateWrapper (issue #45): SkillEntity has
            // several @TableField(updateStrategy = FieldStrategy.ALWAYS)
            // columns (skill_content, config_json, source_code, name_zh,
            // name_en, security_scan_result). Calling updateById with a
            // partial entity would tell MyBatis Plus to write NULL to every
            // ALWAYS column not set on the partial — wiping the imported
            // skill content on every scan write-back.
            LocalDateTime now = LocalDateTime.now();
            skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                    .eq(SkillEntity::getId, entity.getId())
                    .set(SkillEntity::getSecurityScanStatus, newStatus)
                    .set(SkillEntity::getSecurityScanResult, newJson)
                    .set(SkillEntity::getSecurityScanTime, now));
            // Keep the in-memory entity coherent with the DB so the next
            // resolve in the same tick doesn't redundantly write again.
            entity.setSecurityScanStatus(newStatus);
            entity.setSecurityScanResult(newJson);
            entity.setSecurityScanTime(now);
        } catch (Exception e) {
            log.warn("Failed to persist scan outcome for skill '{}': {}", entity.getName(), e.getMessage());
        }
    }

    /**
     * Collapse the resolver's rich security state back into the {@code
     * PASSED / FAILED / null} tri-state used on the row.
     */
    private String deriveScanStatus(ResolvedSkill resolved) {
        if (resolved.isSecurityBlocked()) return "FAILED";
        List<ResolvedSkill.SecurityFinding> findings = resolved.getSecurityFindings();
        if (findings != null && !findings.isEmpty()) return "PASSED"; // scanned and found non-blocking issues
        // No block, no findings — treat as scanned-clean (still PASSED so
        // listEnabledSkills() doesn't treat it as never-scanned).
        return "PASSED";
    }

    private String serializeFindings(List<ResolvedSkill.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (Exception e) {
            log.debug("Failed to serialize findings: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 阶段 1：内容解析 ====================

    private ResolvedSkill resolveFromDirectory(SkillEntity entity, Path skillDir, String configuredDir, String source) {
        Path skillMd = skillDir.resolve("SKILL.md");

        String content = "";
        String description = entity.getDescription();

        if (Files.exists(skillMd)) {
            try {
                content = Files.readString(skillMd);
                SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
                if (!parsed.getDescription().isBlank()) {
                    description = parsed.getDescription();
                }
            } catch (Exception e) {
                log.warn("Failed to read SKILL.md from {}: {}", skillMd, e.getMessage());
            }
        }

        Map<String, Object> references = directoryScanner.buildDirectoryTree(skillDir.resolve("references"));
        Map<String, Object> scripts = directoryScanner.buildDirectoryTree(skillDir.resolve("scripts"));

        return ResolvedSkill.builder()
            .name(entity.getName())
            .description(description)
            .content(content)
            .source(source)
            .skillDir(skillDir)
            .configuredSkillDir(configuredDir)
            .runtimeAvailable(true) // 暂定，后续安全/依赖检查可能改写
            .resolutionError(null)
            .references(references)
            .scripts(scripts)
            .enabled(Boolean.TRUE.equals(entity.getEnabled()))
            .icon(entity.getIcon())
            .builtin(Boolean.TRUE.equals(entity.getBuiltin()))
            .build();
    }

    private ResolvedSkill resolveFromDatabase(SkillEntity entity, String configuredDir) {
        String content = entity.getSkillContent() != null ? entity.getSkillContent() : "";
        String description = entity.getDescription();

        if (!content.isBlank()) {
            SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
            if (!parsed.getDescription().isBlank()) {
                description = parsed.getDescription();
            }
        }

        boolean hasContent = !content.isBlank();
        String error = null;
        if (configuredDir != null) {
            error = "Configured skillDir not found: " + configuredDir;
            if (hasContent) {
                error += " (fallback to database skillContent)";
            }
        } else if (!hasContent) {
            error = "No skillDir configured and no skillContent available";
        }

        return ResolvedSkill.builder()
            .name(entity.getName())
            .description(description)
            .content(content)
            .source("database")
            .skillDir(null)
            .configuredSkillDir(configuredDir)
            .runtimeAvailable(hasContent || (description != null && !description.isBlank()))
            .resolutionError(error)
            .references(Map.of())
            .scripts(Map.of())
            .enabled(Boolean.TRUE.equals(entity.getEnabled()))
            .icon(entity.getIcon())
            .builtin(Boolean.TRUE.equals(entity.getBuiltin()))
            .build();
    }

    // ==================== 阶段 2：安全扫描 ====================

    private void applySecurity(ResolvedSkill resolved) {
        try {
            SkillValidationResult result = securityService.validate(resolved);
            boolean trustedBuiltin = resolved.isBuiltin() && result.isBlocked();
            resolved.setSecurityBlocked(result.isBlocked() && !trustedBuiltin);
            resolved.setSecuritySeverity(result.getMaxSeverity() != null ? result.getMaxSeverity().name() : null);
            if (trustedBuiltin) {
                resolved.setSecuritySummary("Builtin skill trusted: " + result.getSummary());
            } else {
                resolved.setSecuritySummary(result.getSummary());
            }
            resolved.setSecurityWarnings(result.getWarnings());

            // 转换 findings 为 JSON 友好格式
            if (result.getFindings() != null && !result.getFindings().isEmpty()) {
                List<ResolvedSkill.SecurityFinding> secFindings = result.getFindings().stream()
                    .map(f -> ResolvedSkill.SecurityFinding.builder()
                        .ruleId(f.getRuleId())
                        .severity(f.getSeverity() != null ? f.getSeverity().name() : null)
                        .category(f.getCategory())
                        .title(f.getTitle())
                        .description(f.getDescription())
                        .filePath(f.getFilePath())
                        .lineNumber(f.getLineNumber())
                        .snippet(f.getSnippet())
                        .remediation(f.getRemediation())
                        .build())
                    .collect(Collectors.toList());
                resolved.setSecurityFindings(secFindings);
            }

            if (trustedBuiltin) {
                log.warn("Builtin skill '{}' bypassed security block: {}", resolved.getName(), result.getSummary());
            } else if (result.isBlocked()) {
                log.warn("Skill '{}' blocked by security scan: {}", resolved.getName(), result.getSummary());
            } else if (result.getFindings() != null && !result.getFindings().isEmpty()) {
                log.info("Skill '{}' security scan: {} finding(s)", resolved.getName(), result.getFindings().size());
            }
        } catch (Exception e) {
            log.error("Security scan failed for skill '{}': {}", resolved.getName(), e.getMessage());
            resolved.setSecurityWarnings(List.of("Security scan error: " + e.getMessage()));
        }
    }

    // ==================== 阶段 3：依赖检查 ====================

    private void applyDependencyCheck(ResolvedSkill resolved) {
        try {
            // 解析 frontmatter 获取依赖声明
            String content = resolved.getContent();
            if (content == null || content.isBlank()) {
                resolved.setDependencyReady(true);
                resolved.setDependencySummary("No dependencies declared");
                return;
            }

            SkillFrontmatterParser.ParsedSkillMd parsed = frontmatterParser.parse(content);
            SkillFrontmatterParser.SkillDependencies deps = parsed.getDependencies();
            List<String> platforms = parsed.getPlatforms();

            if ((deps == null || deps.isEmpty()) && (platforms == null || platforms.isEmpty())) {
                resolved.setDependencyReady(true);
                resolved.setDependencySummary("No dependencies declared");
                return;
            }

            SkillDependencyChecker.DependencyCheckResult result =
                dependencyChecker.check(deps, platforms, resolved.getName());

            resolved.setDependencyReady(result.isSatisfied());
            resolved.setMissingDependencies(result.getMissing());
            resolved.setDependencySummary(result.getSummary());

            if (!result.isSatisfied()) {
                log.info("Skill '{}' dependencies not satisfied: {}", resolved.getName(), result.getSummary());
            }
        } catch (Exception e) {
            log.error("Dependency check failed for skill '{}': {}", resolved.getName(), e.getMessage());
            resolved.setDependencyReady(true); // 检查失败不阻断
            resolved.setDependencySummary("Dependency check error: " + e.getMessage());
        }
    }

    // ==================== 阶段 4：综合判定 ====================

    private void resolveRuntimeAvailability(ResolvedSkill resolved) {
        // 安全阻断 → 不可用
        if (resolved.isSecurityBlocked()) {
            resolved.setRuntimeAvailable(false);
            if (resolved.getResolutionError() == null) {
                resolved.setResolutionError("Security blocked: " + resolved.getSecuritySummary());
            }
            return;
        }

        // 依赖不满足 → 不可用
        if (!resolved.isDependencyReady()) {
            resolved.setRuntimeAvailable(false);
            if (resolved.getResolutionError() == null) {
                resolved.setResolutionError("Dependencies missing: " + resolved.getDependencySummary());
            }
        }

        // 其他情况保留原有 runtimeAvailable 判定
    }

    // ==================== 工具方法 ====================

    private String extractSkillDirString(SkillEntity entity) {
        String configJson = entity.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            return null;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = objectMapper.readValue(configJson, Map.class);

            String pathStr = null;
            if (config.containsKey("skillDir")) {
                pathStr = config.get("skillDir").toString();
            } else if (config.containsKey("path")) {
                pathStr = config.get("path").toString();
            } else if (config.containsKey("directory")) {
                pathStr = config.get("directory").toString();
            }

            if (pathStr != null && !pathStr.isBlank()) {
                return pathStr;
            }
        } catch (Exception e) {
            log.debug("Failed to parse configJson for skill {}: {}", entity.getName(), e.getMessage());
        }

        return null;
    }
}
