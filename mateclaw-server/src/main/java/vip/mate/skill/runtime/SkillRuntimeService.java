package vip.mate.skill.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.manifest.SkillManifest;
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
public class SkillRuntimeService {

    private final SkillService skillService;
    private final SkillPackageResolver packageResolver;
    /**
     * {@code @Lazy} — SkillLessonsService depends on SkillWorkspaceManager,
     * which is constructed early; the lazy proxy avoids a chicken-and-egg
     * cycle when the runtime service initializes alongside the skill
     * service stack. Using setter-style injection through the
     * constructor below.
     */
    private final SkillLessonsService lessonsService;

    @Autowired
    public SkillRuntimeService(SkillService skillService,
                               SkillPackageResolver packageResolver,
                               @Lazy SkillLessonsService lessonsService) {
        this.skillService = skillService;
        this.packageResolver = packageResolver;
        this.lessonsService = lessonsService;
    }

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
     * RFC-090 §14.1 — single source of truth for "is this resolved
     * skill currently exposable to an agent". Both the global
     * {@link #refreshActiveSkills()} cache and the per-agent
     * {@link #buildSkillPromptEnhancement(Set)} branch route through
     * here so the two views can never disagree.
     *
     * <p>Rules:
     * <ol>
     *   <li>row enabled, runtime resolved, not security-blocked — required</li>
     *   <li>manifest present → at least one feature READY ({@code hasAnyActiveFeature})</li>
     *   <li>manifest absent (legacy SKILL.md) → fall back to
     *       {@code dependencyReady} so old skills behave unchanged</li>
     * </ol>
     */
    public static boolean passesActiveGate(ResolvedSkill s) {
        if (s == null) return false;
        if (!s.isEnabled() || !s.isRuntimeAvailable() || s.isSecurityBlocked()) return false;
        if (s.getManifest() == null) return s.isDependencyReady();
        return s.hasAnyActiveFeature();
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
            .filter(SkillRuntimeService::passesActiveGate)
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
            // Per-agent 过滤：从全局 enabled skills 中按 ID 过滤。RFC-090
            // §14.1 — must use the same features-aware gate as
            // refreshActiveSkills() so legacy dependencyReady drift
            // doesn't silently let setup-needed manifest skills through
            // (or hide partially-ready features that should be visible).
            List<SkillEntity> enabledSkills = skillService.listEnabledSkills();
            activeSkills = enabledSkills.stream()
                    .filter(s -> boundSkillIds.contains(s.getId()))
                    .map(packageResolver::resolve)
                    .filter(SkillRuntimeService::passesActiveGate)
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

        // RFC-090 §11.4.3 + §10.2 Q6 — append per-skill LESSONS.md after
        // the catalog so the LLM sees "Available Skills" first, then any
        // accumulated lessons attached to each skill that has opted in.
        appendLessonsBlock(sb, activeSkills);

        return sb.toString();
    }

    /**
     * Append a "## Lessons learned" block to the prompt enhancement
     * with one subsection per active skill that has lessons recorded.
     *
     * <p>Skills opt in via {@code self-evolution.lessons_enabled} (default
     * true). Skills with no LESSONS.md content contribute nothing — we
     * never emit an empty subsection.
     */
    private void appendLessonsBlock(StringBuilder sb, List<ResolvedSkill> activeSkills) {
        if (lessonsService == null || activeSkills == null || activeSkills.isEmpty()) return;
        StringBuilder lessons = new StringBuilder();
        for (ResolvedSkill skill : activeSkills) {
            SkillManifest manifest = skill.getManifest();
            boolean enabled = manifest == null
                    || manifest.getSelfEvolution() == null
                    || manifest.getSelfEvolution().isLessonsEnabled();
            if (!enabled) continue;
            String body = lessonsService.readLessonsBody(skill);
            if (body == null || body.isBlank()) continue;
            lessons.append("\n### ").append(skill.getName()).append("\n");
            lessons.append(body).append("\n");
        }
        if (lessons.length() > 0) {
            sb.append("\n\n## Lessons learned\n");
            sb.append("Past observations the agent recorded for these skills. ");
            sb.append("Treat them as advisory hints — the canonical SKILL.md still wins on conflict.\n");
            sb.append(lessons);
        }
    }
}
