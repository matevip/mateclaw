package vip.mate.skill.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.service.SkillService;

import vip.mate.skill.workspace.SkillWorkspaceEvent;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 技能运行时服务
 * 管理 active skills 运行时视图，提供缓存和刷新机制
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRuntimeService {

    private final SkillService skillService;
    private final SkillPackageResolver packageResolver;

    // 缓存已解析的 active skills（5分钟过期）
    private final Cache<String, List<ResolvedSkill>> activeSkillsCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10)
        .build();

    private static final String CACHE_KEY = "active_skills";

    @PostConstruct
    public void init() {
        log.info("SkillRuntimeService initialized");
        // 设置反向引用，避免循环依赖
        skillService.setRuntimeService(this);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 延迟到 ApplicationReady 事件触发，确保 SQL 初始化脚本已执行完毕
        refreshActiveSkills();
    }

    @EventListener(SkillWorkspaceEvent.class)
    public void onWorkspaceEvent(SkillWorkspaceEvent event) {
        log.info("Workspace event: {} {} at {}", event.type(), event.skillName(), event.workspacePath());
        refreshActiveSkills();
    }

    /**
     * 获取当前启用的技能列表（运行时视图）
     */
    public List<ResolvedSkill> getActiveSkills() {
        List<ResolvedSkill> cached = activeSkillsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        return refreshActiveSkills();
    }

    /**
     * 刷新 active skills 缓存
     * 进入 active set 的 skill 必须同时满足：
     * 1. enabled == true
     * 2. runtimeAvailable == true
     * 3. securityBlocked == false
     * 4. dependencyReady == true
     */
    public List<ResolvedSkill> refreshActiveSkills() {
        List<SkillEntity> enabledSkills = skillService.listEnabledSkills();

        List<ResolvedSkill> resolved = enabledSkills.stream()
            .map(packageResolver::resolve)
            .filter(ResolvedSkill::isEnabled)
            .filter(ResolvedSkill::isRuntimeAvailable)
            .filter(s -> !s.isSecurityBlocked())
            // RFC-090 §14.1 — features-aware gate. When a manifest is
            // present, require at least one READY feature. Legacy skills
            // (no manifest) fall back to the old dependencyReady boolean.
            .filter(s -> s.getManifest() == null
                    ? s.isDependencyReady()
                    : s.hasAnyActiveFeature())
            .collect(Collectors.toList());

        activeSkillsCache.put(CACHE_KEY, resolved);
        log.info("Refreshed active skills: {} enabled", resolved.size());

        return resolved;
    }

    /**
     * 解析所有技能的运行时状态（管理页面使用，包含 disabled 和 error 信息）
     */
    public List<ResolvedSkill> resolveAllSkillsStatus() {
        List<SkillEntity> allSkills = skillService.listSkills();
        return allSkills.stream()
            .map(packageResolver::resolve)
            .collect(Collectors.toList());
    }

    /**
     * Rescan one skill on demand (RFC-042 §2.3.4) — runs the full resolver
     * pipeline (content + security + dependency), which writes the updated
     * scan result to DB as a side-effect, and then invalidates the active
     * skills cache so subsequent reads reflect the new status.
     */
    public ResolvedSkill rescanSingle(SkillEntity skill) {
        ResolvedSkill resolved = packageResolver.resolve(skill);
        activeSkillsCache.invalidateAll();
        log.info("Rescanned skill '{}' (id={}): status={}, blocked={}",
                skill.getName(), skill.getId(),
                skill.getSecurityScanStatus(), resolved.isSecurityBlocked());
        return resolved;
    }

    /**
     * 根据名称查找 active skill
     */
    public ResolvedSkill findActiveSkill(String name) {
        return getActiveSkills().stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 构建技能 prompt 增强片段（全局，向后兼容）
     */
    public String buildSkillPromptEnhancement() {
        return buildSkillPromptEnhancement(null);
    }

    /**
     * 构建技能 prompt 增强片段（支持 per-agent 过滤）
     *
     * @param boundSkillIds Agent 绑定的 skill ID 集合。null 表示使用全局默认（无绑定）。
     *                      非 null 时仅包含指定 ID 的 skill。
     */
    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds) {
        List<ResolvedSkill> activeSkills;
        if (boundSkillIds != null) {
            // Per-agent 过滤：从全局 enabled skills 中按 ID 过滤
            List<SkillEntity> enabledSkills = skillService.listEnabledSkills();
            activeSkills = enabledSkills.stream()
                    .filter(s -> boundSkillIds.contains(s.getId()))
                    .map(packageResolver::resolve)
                    .filter(ResolvedSkill::isEnabled)
                    .filter(ResolvedSkill::isRuntimeAvailable)
                    .filter(s -> !s.isSecurityBlocked())
                    .filter(ResolvedSkill::isDependencyReady)
                    .collect(java.util.stream.Collectors.toList());
        } else {
            activeSkills = getActiveSkills();
        }
        if (activeSkills.isEmpty()) {
            return "";
        }

        // Issue #46 + #49 prompt rewrite. Stop-gap until RFC-090 lands the
        // proper `type` enum and inlines `type: prompt` skill bodies into the
        // system prompt directly. Today every skill is announced via this
        // catalog and the LLM has to pull SKILL.md on demand. Two failure
        // modes have been observed:
        //   #46 — LLM treated the skill name as a tool ("tool_use{name=
        //         RedisOps}"), so we lead with an explicit "NOT callable"
        //         warning and surface the actual tool names readSkillFile /
        //         runSkillScript.
        //   #49 — On a docs-only skill (SKILL.md, no scripts/ dir), the LLM
        //         tried to invoke a non-existent scripts/ file and never
        //         followed SKILL.md's text guidance. The previous wording
        //         "usually that means calling runSkillScript(...)" actively
        //         pushed it that way. Fix: tell the model the two shapes
        //         exist, mark each row's shape, and forbid invoking scripts
        //         on docs-only skills.
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available Skills\n\n");
        sb.append("⚠️  **Skills are documentation packages, NOT directly callable tools.**\n");
        sb.append("Calling a skill name as a tool (e.g. tool_use{name=\"RedisOps\"}) will fail with \"Tool not found\". ");
        sb.append("To use a skill:\n\n");
        sb.append("1. ALWAYS first read its SKILL.md to learn how it works:\n");
        sb.append("   `readSkillFile(skillName=\"<name>\", filePath=\"SKILL.md\")`\n");
        sb.append("2. Then follow what SKILL.md says. Skills come in two shapes — check the **Shape** column below:\n");
        sb.append("   - **docs only** — no `scripts/` directory exists. SKILL.md is the entire instruction set; follow its text guidance directly. Do NOT call `runSkillScript` on these — the script does not exist and the call will fail.\n");
        sb.append("   - **scripts + docs** — `scripts/` is present. SKILL.md will name the script to run; invoke it with `runSkillScript(skillName=\"<name>\", scriptPath=\"scripts/<file>\")`.\n");
        sb.append("   Either shape may also expose supplementary docs via `readSkillFile(skillName=\"<name>\", filePath=\"references/<file>\")`.\n\n");

        // Concrete example anchored to the first enabled skill so the LLM
        // sees a real name it just read in the listing below.
        String exampleName = activeSkills.get(0).getName();
        sb.append("Concrete example — to use the `").append(exampleName).append("` skill, START with:\n");
        sb.append("  `readSkillFile(skillName=\"").append(exampleName).append("\", filePath=\"SKILL.md\")`\n\n");

        sb.append("### Enabled skills\n");
        sb.append("Pass these names as the `skillName=` argument to `readSkillFile` / `runSkillScript`. ");
        sb.append("Do **not** call them as tools.\n\n");
        sb.append("| Skill name | Shape | Description |\n");
        sb.append("|------------|-------|-------------|\n");
        for (ResolvedSkill skill : activeSkills) {
            // `scripts` is populated by SkillDirectoryScanner — empty map both
            // for "scripts/ directory absent" and "directory present but empty".
            // Either way, runSkillScript has nothing to call, so we report the
            // skill as docs-only. (Database-fallback skills also land here
            // because SkillPackageResolver.resolveFromDatabase sets it to
            // Map.of().) RFC-090's `type` field will replace this heuristic.
            boolean hasScripts = skill.getScripts() != null && !skill.getScripts().isEmpty();
            String shape = hasScripts ? "scripts + docs" : "docs only";
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ").append(shape).append(" | ");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 200) {
                    desc = desc.substring(0, 200) + "...";
                }
                // Escape pipe and newline so a multi-line description doesn't
                // break the table layout.
                sb.append(desc.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }

        return sb.toString();
    }
}
