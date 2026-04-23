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

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available Skills\n");
        sb.append("以下技能已启用，你可以通过 skill runtime tools 使用它们：\n\n");

        for (ResolvedSkill skill : activeSkills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 200) {
                    desc = desc.substring(0, 200) + "...";
                }
                sb.append(" — ").append(desc);
            }
            sb.append("\n");
        }

        sb.append("\n### 如何使用技能\n");
        sb.append("1. 使用 `read_skill_file` 工具读取技能内部文件（SKILL.md / references / scripts）\n");
        sb.append("2. 使用 `run_skill_script` 工具执行技能脚本\n");
        sb.append("3. 所有路径相对技能根目录解析，必须以 references/ 或 scripts/ 开头\n");

        return sb.toString();
    }
}
