package vip.mate.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.SkillWorkspaceProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能业务服务
 * <p>
 * 负责技能的 CRUD 管理、启用/禁用控制，以及与 Agent 运行时的集成。
 * Skill 在 MateClaw 中的定位是"可扩展的能力模块"，分为三种类型：
 * <ul>
 *   <li>builtin — 系统内置技能（不可删除），通常对应预定义的 systemPrompt 片段</li>
 *   <li>mcp — 通过 MCP 协议连接外部工具服务器</li>
 *   <li>dynamic — 用户自定义的动态技能（可包含脚本或配置）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillMapper skillMapper;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillWorkspaceProperties workspaceProperties;
    private vip.mate.skill.runtime.SkillRuntimeService runtimeService;

    /**
     * 延迟注入 SkillRuntimeService 避免循环依赖
     */
    public void setRuntimeService(vip.mate.skill.runtime.SkillRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    // ==================== CRUD ====================

    /**
     * 获取所有技能列表（管理页面使用）
     * 排序：内置优先，然后按创建时间倒序
     */
    public List<SkillEntity> listSkills() {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .orderByDesc(SkillEntity::getBuiltin)
                .orderByDesc(SkillEntity::getCreateTime));
    }

    /**
     * Paginated skill listing for the SkillMarket admin UI.
     *
     * <p>RFC-042 §2.1 — replaces the unbounded {@code /skills} list. Filters
     * are all optional; empty or {@code null} means "no filter". Keyword
     * searches name / description / tags with LIKE.
     *
     * <p>{@code scanStatus} (RFC-042 §2.3.5) filters on {@code
     * security_scan_status}: {@code "FAILED"} surfaces blocked skills so the
     * admin can inspect findings and rescan, {@code "PASSED"} shows scanned
     * clean rows, {@code null} / empty means no scan filter.
     */
    public IPage<SkillEntity> pageSkills(int page, int size, String keyword,
                                          String skillType, Boolean enabled,
                                          String scanStatus) {
        Page<SkillEntity> pageParam = new Page<>(Math.max(page, 1), Math.max(size, 1));
        LambdaQueryWrapper<SkillEntity> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(SkillEntity::getName, kw)
                    .or().like(SkillEntity::getDescription, kw)
                    .or().like(SkillEntity::getTags, kw));
        }
        if (skillType != null && !skillType.isBlank()) {
            wrapper.eq(SkillEntity::getSkillType, skillType);
        }
        if (enabled != null) {
            wrapper.eq(SkillEntity::getEnabled, enabled);
        }
        if (scanStatus != null && !scanStatus.isBlank()) {
            wrapper.eq(SkillEntity::getSecurityScanStatus, scanStatus.trim().toUpperCase());
        }

        // Builtin first ('builtin' < 'dynamic' < 'mcp' alphabetically), then by
        // name for a stable order. Sorting on skill_type instead of the
        // `builtin` boolean because SkillInstaller leaves the boolean NULL on
        // user-installed rows; sorting on name instead of create_time because
        // the 20 seeded builtins share a near-identical create_time and looked
        // random within the group (issue #48).
        wrapper.orderByAsc(SkillEntity::getSkillType)
               .orderByAsc(SkillEntity::getName);

        return skillMapper.selectPage(pageParam, wrapper);
    }

    /**
     * Manually re-run security + dependency resolution for a single skill
     * (RFC-042 §2.3.4). Triggered from the admin UI after the user fixes
     * flagged code and wants an immediate verdict instead of waiting for
     * the next refresh event.
     *
     * <p>The resolver itself persists the outcome — this method just kicks
     * it and returns the reloaded row.
     */
    public SkillEntity rescanSecurity(Long id) {
        SkillEntity skill = getSkill(id); // throws MateClawException if missing
        if (runtimeService == null) {
            throw new MateClawException("err.skill.runtime_unavailable",
                    "Skill runtime not initialized yet; retry in a moment");
        }
        runtimeService.rescanSingle(skill);
        return skillMapper.selectById(id);
    }

    /**
     * Aggregate skill counts per {@code skill_type}, plus an {@code all}
     * rollup. Feeds the SkillMarket tab badges without pulling every row.
     */
    public Map<String, Long> countByType() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("all", skillMapper.selectCount(null));
        for (String type : List.of("builtin", "mcp", "dynamic")) {
            result.put(type, skillMapper.selectCount(
                    new LambdaQueryWrapper<SkillEntity>()
                            .eq(SkillEntity::getSkillType, type)));
        }
        return result;
    }

    /**
     * 获取已启用的技能列表（Agent 运行时使用）
     * <p>
     * RFC-023：追加 security_scan_status 过滤——FAILED 的 skill 不加载。
     * NULL（旧数据/手动创建）和 PASSED（扫描通过）都允许。
     */
    public List<SkillEntity> listEnabledSkills() {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getEnabled, true)
                .and(w -> w.isNull(SkillEntity::getSecurityScanStatus)
                           .or().eq(SkillEntity::getSecurityScanStatus, "PASSED"))
                .orderByAsc(SkillEntity::getName));
    }

    /**
     * 按名称查找技能（RFC-023：SkillManageTool 重名检查用）
     */
    public SkillEntity findByName(String name) {
        return skillMapper.selectOne(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getName, name)
                .last("LIMIT 1"));
    }

    /**
     * 按类型获取技能列表
     */
    public List<SkillEntity> listSkillsByType(String skillType) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getSkillType, skillType)
                .orderByDesc(SkillEntity::getCreateTime));
    }

    /**
     * 获取技能详情
     */
    public SkillEntity getSkill(Long id) {
        SkillEntity skill = skillMapper.selectById(id);
        if (skill == null) {
            throw new MateClawException("err.skill.not_found", "技能不存在: " + id);
        }
        return skill;
    }

    /**
     * 创建技能
     * 默认类型为 dynamic（用户自定义），非内置
     */
    public SkillEntity createSkill(SkillEntity skill) {
        // 验证名称不为空
        if (skill.getName() == null || skill.getName().isBlank()) {
            throw new MateClawException("err.skill.name_required", "技能名称不能为空");
        }

        // 检查名称唯一性
        Long count = skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getName, skill.getName()));
        if (count > 0) {
            throw new MateClawException("err.skill.name_exists", "技能名称已存在: " + skill.getName());
        }

        // 设置默认值
        skill.setBuiltin(false);
        if (skill.getEnabled() == null) {
            skill.setEnabled(true);
        }
        // 前端只识别 builtin/mcp/dynamic，用户新建默认为 dynamic
        if (skill.getSkillType() == null || skill.getSkillType().isBlank()) {
            skill.setSkillType("dynamic");
        }
        // 默认版本号
        if (skill.getVersion() == null || skill.getVersion().isBlank()) {
            skill.setVersion("1.0.0");
        }

        skillMapper.insert(skill);
        log.info("Created skill: {} (type={})", skill.getName(), skill.getSkillType());

        // 自动初始化工作区目录
        if (workspaceProperties.isAutoInit() && !hasExplicitSkillDir(skill)) {
            workspaceManager.initWorkspace(skill.getName(), skill.getSkillContent());
        }

        // 刷新 runtime cache
        if (runtimeService != null) {
            runtimeService.refreshActiveSkills();
        }

        return skill;
    }

    /**
     * 更新技能
     * 内置技能只允许修改 enabled、configJson、description
     */
    public SkillEntity updateSkill(SkillEntity skill) {
        SkillEntity existing = getSkill(skill.getId());

        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            // 内置技能：只允许修改有限字段
            existing.setEnabled(skill.getEnabled() != null ? skill.getEnabled() : existing.getEnabled());
            existing.setConfigJson(skill.getConfigJson());
            existing.setDescription(skill.getDescription() != null ? skill.getDescription() : existing.getDescription());
            // builtin skill 也允许更新 skillContent（用于维护 fallback 内容）
            if (skill.getSkillContent() != null) {
                existing.setSkillContent(skill.getSkillContent());
            }
            skillMapper.updateById(existing);
            log.info("Updated builtin skill (limited): {}", existing.getName());

            // builtin skill 也同步 workspace SKILL.md
            syncSkillContentToWorkspace(existing);

            // 刷新 runtime cache
            if (runtimeService != null) {
                runtimeService.refreshActiveSkills();
            }

            return existing;
        }

        // 非内置技能：允许修改所有字段，但不允许改为 builtin
        skill.setBuiltin(false);
        skillMapper.updateById(skill);
        log.info("Updated skill: {}", skill.getName());

        // 若 skillContent 变更且约定工作区存在，同步 SKILL.md
        syncSkillContentToWorkspace(skill);

        // 刷新 runtime cache
        if (runtimeService != null) {
            runtimeService.refreshActiveSkills();
        }

        return skill;
    }

    /**
     * RFC-090 §14.5 — uninstall path: logical delete (row stays in DB
     * with {@code deleted=1}) + archive workspace to
     * {@code .archived/}.
     *
     * <p>Recoverable: the user can re-install a skill of the same name
     * later, since the archived workspace and soft-deleted row stay
     * out of every standard query but on disk.
     *
     * <p>Builtin skills are protected: uninstalling a builtin would
     * leave the seed re-creating it on next boot, so we surface a
     * clear error instead of doing partial work.
     */
    public void uninstallSkill(Long id) {
        SkillEntity skill = getSkill(id);
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            throw new MateClawException("err.skill.builtin_readonly",
                    "内置技能不可卸载: " + skill.getName());
        }
        skillMapper.deleteById(id); // logical delete (deleted=1)
        log.info("Uninstalled skill (logical delete + archive): {}", skill.getName());

        if ("archive".equals(workspaceProperties.getDeletePolicy())) {
            workspaceManager.archiveWorkspace(skill.getName());
        }
        // RFC-090 review #3 — refresh won't deregister wrappers for a
        // soft-deleted row (it only resolves rows still in
        // listEnabledSkills), so do it explicitly here.
        if (runtimeService != null) {
            runtimeService.deregisterSkillWrappers(id);
            runtimeService.refreshActiveSkills();
        }
    }

    /**
     * RFC-090 §14.5 — hard-delete path: physical SQL delete + workspace
     * purge. Admin only. Not recoverable.
     *
     * <p>Use this when you need to free the slug for an unrelated skill
     * or scrub a row that's been corrupted. UI buttons should call
     * {@link #uninstallSkill} instead unless the user explicitly chose
     * "permanently delete".
     */
    public void hardDeleteSkill(Long id) {
        SkillEntity skill = getSkill(id);
        if (Boolean.TRUE.equals(skill.getBuiltin())) {
            throw new MateClawException("err.skill.builtin_readonly",
                    "内置技能不可硬删除: " + skill.getName());
        }
        skillMapper.hardDeleteById(id); // bypass the logical-delete flag
        log.info("Hard-deleted skill (physical delete + purge): {}", skill.getName());

        workspaceManager.purgeWorkspace(skill.getName());

        // RFC-090 review #3 — same explicit deregister as uninstall.
        if (runtimeService != null) {
            runtimeService.deregisterSkillWrappers(id);
            runtimeService.refreshActiveSkills();
        }
    }

    /**
     * Legacy alias maintained for callers that still use {@code
     * deleteSkill}. Routes to the user-friendly uninstall semantics
     * (logical delete + archive). New code should pick {@link
     * #uninstallSkill} or {@link #hardDeleteSkill} explicitly.
     *
     * @deprecated Use {@link #uninstallSkill} for user-facing UI delete
     *             (recoverable) or {@link #hardDeleteSkill} for admin
     *             "permanent" delete.
     */
    @Deprecated
    public void deleteSkill(Long id) {
        uninstallSkill(id);
    }

    /**
     * 启用/禁用技能
     */
    public SkillEntity toggleSkill(Long id, boolean enabled) {
        SkillEntity skill = getSkill(id);
        skill.setEnabled(enabled);
        skillMapper.updateById(skill);
        log.info("Skill {} {}", skill.getName(), enabled ? "enabled" : "disabled");

        // RFC-090 review #3 — when disabling, explicitly tear down any
        // registered wrapper tools (knowledge / acp). Without this the
        // wrappers stay advertised because the availability supplier
        // closes over the snapshot ResolvedSkill captured at registration.
        if (!enabled && runtimeService != null) {
            runtimeService.deregisterSkillWrappers(id);
        }

        // 刷新 runtime cache
        if (runtimeService != null) {
            runtimeService.refreshActiveSkills();
        }

        return skill;
    }

    // ==================== Agent 运行时集成 ====================

    /**
     * Token 预算上限（字符数近似值，1 token ≈ 2 个中文字 / 4 个英文字符）
     * 默认 6000 字符 ≈ ~2000 tokens，为对话上下文预留足够空间
     */
    private static final int DEFAULT_SKILL_PROMPT_BUDGET = 6000;

    /**
     * 构建技能 Prompt 增强片段（带 Token 预算控制）
     * <p>
     * 优化策略（对比旧版全量注入）：
     * <ol>
     *   <li>分层注入：先注入「技能目录」（名称+描述），再按预算注入「技能详情」（skillContent）</li>
     *   <li>Token 预算控制：总字符数超过预算时，截断详情部分，只保留目录</li>
     *   <li>优先级：builtin 技能优先注入详情，其次按名称排序</li>
     *   <li>不再将 sourceCode 全量注入（旧版会爆 token），改用 skillContent（SKILL.md 协议）</li>
     * </ol>
     *
     * @return systemPrompt 增强片段，可直接拼接到 Agent 的 systemPrompt 末尾
     */
    public String buildSkillPromptEnhancement() {
        return buildSkillPromptEnhancement(DEFAULT_SKILL_PROMPT_BUDGET);
    }

    /**
     * 构建技能 Prompt 增强片段（可指定 Token 预算）
     *
     * @param charBudget 最大字符预算（超出时自动截断详情）
     */
    public String buildSkillPromptEnhancement(int charBudget) {
        List<SkillEntity> enabledSkills = listEnabledSkills();
        if (enabledSkills.isEmpty()) {
            return "";
        }

        // --- 第零层：Skill 自治引导（RFC-023，对标 hermes-agent prompt_builder.py:164-171） ---
        StringBuilder catalog = new StringBuilder();
        catalog.append("\n\n## Skill Management\n\n");
        catalog.append("After completing a complex task (5+ tool calls), fixing a tricky error, ");
        catalog.append("or discovering a non-trivial workflow, save the approach as a skill using ");
        catalog.append("`skill_manage(action='create')` so you can reuse it next time.\n\n");
        catalog.append("When using a skill and finding it outdated, incomplete, or wrong, ");
        catalog.append("patch it immediately with `skill_manage(action='patch')` — don't wait to be asked. ");
        catalog.append("Skills that aren't maintained become liabilities.\n\n");

        // --- 第一层：技能目录（始终注入，消耗很少的 token） ---
        catalog.append("## Available Skills\n");
        catalog.append("以下技能已启用，你可以在对话中根据用户需求灵活运用：\n\n");

        for (SkillEntity skill : enabledSkills) {
            catalog.append("- **").append(skill.getName()).append("**");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                catalog.append(" ").append(skill.getIcon());
            }
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                // 截取描述前 200 字符作为摘要
                String desc = skill.getDescription();
                if (desc.length() > 200) {
                    desc = desc.substring(0, 200) + "...";
                }
                catalog.append(" — ").append(desc);
            }
            catalog.append("\n");
        }

        int remaining = charBudget - catalog.length();
        if (remaining <= 200) {
            // 预算不足，只返回目录
            return catalog.toString();
        }

        // --- 第二层：技能详情（按优先级注入，受预算控制） ---
        // 排序优先级：builtin > 其他，然后按名称
        List<SkillEntity> sorted = enabledSkills.stream()
                .sorted((a, b) -> {
                    int builtinCmp = Boolean.compare(
                            Boolean.TRUE.equals(b.getBuiltin()),
                            Boolean.TRUE.equals(a.getBuiltin()));
                    return builtinCmp != 0 ? builtinCmp : a.getName().compareTo(b.getName());
                })
                .toList();

        StringBuilder details = new StringBuilder();
        details.append("\n### Skill Details\n");
        int detailLen = details.length();

        for (SkillEntity skill : sorted) {
            String content = resolveSkillContent(skill);
            if (content == null || content.isBlank()) {
                continue;
            }

            // 每个技能的详情块
            StringBuilder block = new StringBuilder();
            block.append("\n#### ").append(skill.getName()).append("\n");
            block.append(content).append("\n");

            // 检查预算
            if (detailLen + block.length() > remaining) {
                // 预算不足，尝试截断当前 skill 内容
                int maxContentLen = remaining - detailLen - 60; // 留 60 字符给标题和截断提示
                if (maxContentLen > 200) {
                    block.setLength(0);
                    block.append("\n#### ").append(skill.getName()).append("\n");
                    block.append(content, 0, Math.min(content.length(), maxContentLen));
                    block.append("\n...(truncated)\n");
                    details.append(block);
                }
                break; // 预算用尽，停止注入
            }

            details.append(block);
            detailLen += block.length();
        }

        return catalog.toString() + details;
    }

    /**
     * 获取技能的可注入内容
     * <p>
     * 优先级：skillContent（SKILL.md 协议） > description
     * 不再使用 sourceCode（可能包含大量代码，容易爆 token）
     */
    private String resolveSkillContent(SkillEntity skill) {
        // 优先使用 SKILL.md 内容（执行协议）
        if (skill.getSkillContent() != null && !skill.getSkillContent().isBlank()) {
            return skill.getSkillContent();
        }
        // 回退到 description（兼容旧数据）
        return skill.getDescription();
    }

    /**
     * 获取已启用技能的摘要信息（用于 Agent 状态展示）
     */
    public Map<String, List<String>> getEnabledSkillSummary() {
        return listEnabledSkills().stream()
                .collect(Collectors.groupingBy(
                        SkillEntity::getSkillType,
                        Collectors.mapping(SkillEntity::getName, Collectors.toList())
                ));
    }

    // ==================== Workspace 集成辅助方法 ====================

    /**
     * 同步 skillContent 到工作区 SKILL.md
     */
    private void syncSkillContentToWorkspace(SkillEntity skill) {
        if (skill.getSkillContent() == null || skill.getSkillContent().isBlank()) {
            return;
        }
        if (workspaceManager.conventionWorkspaceExists(skill.getName())) {
            Path workspaceDir = workspaceManager.resolveConventionPath(skill.getName());
            Path skillMd = workspaceDir.resolve("SKILL.md");
            try {
                Files.writeString(skillMd, skill.getSkillContent());
                log.debug("Synced skillContent to workspace SKILL.md: {}", skillMd);
            } catch (Exception e) {
                log.warn("Failed to sync skillContent to workspace: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查 skill 是否有显式配置的 skillDir
     */
    private boolean hasExplicitSkillDir(SkillEntity skill) {
        String configJson = skill.getConfigJson();
        if (configJson == null || configJson.isBlank()) {
            return false;
        }
        return configJson.contains("skillDir") || configJson.contains("path") || configJson.contains("directory");
    }
}
