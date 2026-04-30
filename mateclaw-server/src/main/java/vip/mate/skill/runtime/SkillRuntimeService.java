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
            .filter(ResolvedSkill::isDependencyReady)
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

        // Issue #46 prompt rewrite. Three deliberate choices vs. the old
        // version, all driven by observed LLM mis-calls (e.g. calling
        // "RedisOps" directly as a tool):
        //   1. Lead with an explicit warning that skills are NOT callable.
        //      Primacy bias — putting it first is what makes it stick.
        //   2. Use the actual camelCase tool names (readSkillFile /
        //      runSkillScript). The old text said `read_skill_file` /
        //      `run_skill_script`, which don't exist in the tool registry,
        //      so even an LLM trying to comply couldn't find them.
        //   3. Concrete worked example: "to use RedisOps, START with
        //      readSkillFile(...)". Abstract instructions reliably lose
        //      to a worked example in tool-use prompting.
        //   4. Render the listing as a markdown table with the call pattern
        //      explicit, instead of `- **Name** — desc` which looks
        //      identical to a tool list and primes the model to call
        //      the name directly.
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available Skills\n\n");
        sb.append("⚠️  **Skills are documentation packages, NOT directly callable tools.**\n");
        sb.append("Calling a skill name as a tool (e.g. tool_use{name=\"RedisOps\"}) will fail with \"Tool not found\". ");
        sb.append("To use a skill, follow this two-step pattern:\n\n");
        sb.append("1. Read its SKILL.md to learn how it works:\n");
        sb.append("   `readSkillFile(skillName=\"<name>\", filePath=\"SKILL.md\")`\n");
        sb.append("2. Follow what SKILL.md tells you — usually that means calling:\n");
        sb.append("   `runSkillScript(skillName=\"<name>\", scriptPath=\"scripts/<file>\")` or\n");
        sb.append("   `readSkillFile(skillName=\"<name>\", filePath=\"references/<file>\")`\n\n");

        // Concrete example anchored to the first enabled skill so the LLM
        // sees a real name it just read in the listing below.
        String exampleName = activeSkills.get(0).getName();
        sb.append("Concrete example — to use the `").append(exampleName).append("` skill, START with:\n");
        sb.append("  `readSkillFile(skillName=\"").append(exampleName).append("\", filePath=\"SKILL.md\")`\n\n");

        sb.append("### Enabled skills\n");
        sb.append("Pass these names as the `skillName=` argument to `readSkillFile` / `runSkillScript`. ");
        sb.append("Do **not** call them as tools.\n\n");
        sb.append("| Skill name | Description |\n");
        sb.append("|------------|-------------|\n");
        for (ResolvedSkill skill : activeSkills) {
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ");
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
