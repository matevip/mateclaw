package vip.mate.skill.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.service.AgentBindingService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.SkillDependencyChecker;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.synthesis.SkillSynthesisService;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.workspace.SkillWorkspaceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能管理接口
 * <p>
 * 提供技能的 CRUD、启用/禁用、按类型查询、技能摘要等能力。
 * 对应前端 SkillMarket 页面。
 *
 * @author MateClaw Team
 */
@Tag(name = "技能管理")
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final SkillRuntimeService skillRuntimeService;
    private final SkillWorkspaceManager workspaceManager;
    private final SkillSynthesisService synthesisService;
    private final SkillDependencyChecker dependencyChecker;
    private final SkillLessonsService lessonsService;
    private final AgentSkillBindingMapper agentSkillBindingMapper;
    private final AgentService agentService;
    private final AgentBindingService agentBindingService;

    @Operation(summary = "获取技能分页列表（RFC-042 §2.1）")
    @GetMapping
    public R<IPage<SkillEntity>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String skillType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String scanStatus) {
        return R.ok(skillService.pageSkills(page, size, keyword, skillType, enabled, scanStatus));
    }

    @Operation(summary = "获取各类型技能计数（tab 徽章用）")
    @GetMapping("/counts")
    public R<Map<String, Long>> counts() {
        return R.ok(skillService.countByType());
    }

    @Operation(summary = "重新扫描单个技能（RFC-042 §2.3.4）")
    @PostMapping("/{id}/rescan")
    public R<SkillEntity> rescan(@PathVariable Long id) {
        return R.ok(skillService.rescanSecurity(id));
    }

    @Operation(summary = "获取已启用技能列表")
    @GetMapping("/enabled")
    public R<List<SkillEntity>> listEnabled() {
        return R.ok(skillService.listEnabledSkills());
    }

    @Operation(summary = "按类型获取技能列表")
    @GetMapping("/type/{skillType}")
    public R<List<SkillEntity>> listByType(@PathVariable String skillType) {
        return R.ok(skillService.listSkillsByType(skillType));
    }

    @Operation(summary = "获取已启用技能摘要（按类型分组）")
    @GetMapping("/summary")
    public R<Map<String, List<String>>> summary() {
        return R.ok(skillService.getEnabledSkillSummary());
    }

    @Operation(summary = "获取技能详情")
    @GetMapping("/{id}")
    public R<SkillEntity> get(@PathVariable Long id) {
        return R.ok(skillService.getSkill(id));
    }

    @Operation(summary = "创建技能")
    @PostMapping
    public R<SkillEntity> create(@RequestBody SkillEntity skill) {
        return R.ok(skillService.createSkill(skill));
    }

    @Operation(summary = "更新技能")
    @PutMapping("/{id}")
    public R<SkillEntity> update(@PathVariable Long id, @RequestBody SkillEntity skill) {
        skill.setId(id);
        return R.ok(skillService.updateSkill(skill));
    }

    /**
     * RFC-090 §14.5 — admin-only hard delete: physical row removal +
     * workspace purge. UI surfaces this as "permanently delete" and
     * confirms with a destructive warning. The routine user-facing
     * "remove" button on the skill card calls
     * {@code DELETE /skills/install/{name}} instead, which goes through
     * {@link vip.mate.skill.installer.SkillInstaller#uninstall} for the
     * recoverable logical-delete + archive path.
     */
    @Operation(summary = "硬删除技能 (admin only — 物理删除 + 工作区清空)")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        skillService.hardDeleteSkill(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用技能")
    @PutMapping("/{id}/toggle")
    public R<SkillEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        return R.ok(skillService.toggleSkill(id, enabled));
    }

    @Operation(summary = "预览技能 Prompt 增强效果（调试用，与 Agent 真实运行时一致）")
    @GetMapping("/prompt-preview")
    public R<Map<String, Object>> promptPreview() {
        String prompt = skillRuntimeService.buildSkillPromptEnhancement();
        return R.ok(Map.of(
                "actualLength", prompt.length(),
                "estimatedTokens", prompt.length() / 3,
                "prompt", prompt
        ));
    }

    // ==================== Runtime API ====================

    @Operation(summary = "获取 active skills 运行时视图")
    @GetMapping("/runtime/active")
    public R<Map<String, Object>> getActiveSkills() {
        List<ResolvedSkill> skills = skillRuntimeService.getActiveSkills();
        return R.ok(Map.of("count", skills.size(), "skills", skills));
    }

    @Operation(summary = "获取所有技能的运行时解析状态（管理页面使用）")
    @GetMapping("/runtime/status")
    public R<List<ResolvedSkill>> getRuntimeStatus() {
        return R.ok(skillRuntimeService.resolveAllSkillsStatus());
    }

    @Operation(summary = "刷新 active skills 缓存，resync=true 时同步内置技能到 workspace")
    @PostMapping("/runtime/refresh")
    public R<Map<String, Object>> refreshRuntime(
            @RequestParam(defaultValue = "false") boolean resync) {
        List<String> resynced = List.of();
        if (resync) {
            resynced = workspaceManager.syncBundledSkills();
        }
        List<ResolvedSkill> skills = skillRuntimeService.refreshActiveSkills();
        return R.ok(Map.of(
                "count", skills.size(),
                "message", resync ? "Active skills refreshed with workspace resync" : "Active skills refreshed",
                "resynced", resynced));
    }

    // ==================== Requirements API (RFC-090 §7) ====================

    /**
     * RFC-090 §7 — pre-flight check: returns the per-requirement status
     * for the skill so the install dialog and the detail drawer can render
     * "✓ ffmpeg detected / ✗ groq_key missing" rows.
     *
     * <p>Calls the same {@link SkillDependencyChecker} the runtime uses,
     * so the UI never disagrees with the runtime gate.
     */
    @Operation(summary = "Pre-flight requirement statuses for a skill (RFC-090)")
    @GetMapping("/{id}/requirements")
    public R<Map<String, Object>> requirements(@PathVariable Long id) {
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            return R.fail("Skill not found: " + id);
        }
        SkillManifest manifest = resolved.getManifest();
        if (manifest == null) {
            // Legacy skill — fall back to dependency summary already on the resolved view.
            return R.ok(Map.of(
                    "allMet", resolved.isDependencyReady(),
                    "statuses", List.of(),
                    "summary", resolved.getDependencySummary() != null ? resolved.getDependencySummary() : ""
            ));
        }

        List<Map<String, Object>> statuses = new ArrayList<>();
        boolean allMet = true;
        for (SkillManifest.RequirementDef req : manifest.getRequires()) {
            SkillDependencyChecker.RequirementStatus st = dependencyChecker.checkRequirement(req);
            boolean satisfied = st == SkillDependencyChecker.RequirementStatus.SATISFIED;
            if (!satisfied && !req.isOptional()) allMet = false;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", req.getKey());
            row.put("type", req.getType());
            row.put("description", req.getDescription());
            row.put("optional", req.isOptional());
            row.put("status", st.name());
            row.put("satisfied", satisfied);
            row.put("installCommands", req.getInstall());
            statuses.add(row);
        }
        return R.ok(Map.of(
                "allMet", allMet,
                "statuses", statuses,
                "featureStatuses", resolved.getFeatureStatuses(),
                "activeFeatures", resolved.getActiveFeatures()
        ));
    }

    // ==================== Reverse lookup (RFC-090 §7) ====================

    /**
     * RFC-090 §7 / §14.2 — list agents (employees) for which this
     * skill is reachable.
     *
     * <p>Two coverage paths, both reflected in the response:
     * <ol>
     *   <li><b>Explicit</b> — there's a {@code mate_agent_skill} row
     *       with this skill id and {@code enabled=true}.</li>
     *   <li><b>Implicit</b> — the agent has no explicit skill binding
     *       at all ({@code AgentBindingService.getBoundSkillIds}
     *       returns null), which the three-state contract treats as
     *       "use every globally-enabled skill". Most users never wire
     *       explicit bindings, so without this branch the count was
     *       always zero even for skills clearly visible to the LLM.</li>
     * </ol>
     *
     * <p>Each row carries {@code binding: "explicit" | "implicit"} so
     * the UI can label the relationship.
     */
    @Operation(summary = "List agents that can use this skill (RFC-090 §14.2)")
    @GetMapping("/{id}/employees")
    public R<List<Map<String, Object>>> employees(@PathVariable Long id) {
        // Explicit bindings: agent_skill rows pointing to this skill.
        List<AgentSkillBinding> explicitBindings = agentSkillBindingMapper.selectList(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getSkillId, id)
                        .eq(AgentSkillBinding::getEnabled, true));
        java.util.Set<Long> explicitAgentIds = new java.util.LinkedHashSet<>();
        for (AgentSkillBinding b : explicitBindings) explicitAgentIds.add(b.getAgentId());

        // Implicit bindings: agents whose getBoundSkillIds() == null
        // (no explicit row in agent_skill at all). They get every
        // globally-enabled skill, which includes this one provided the
        // skill itself is enabled.
        List<AgentEntity> allAgents = agentService.listAgents();
        java.util.Set<Long> implicitAgentIds = new java.util.LinkedHashSet<>();
        SkillEntity skill;
        try {
            skill = skillService.getSkill(id);
        } catch (Exception e) {
            skill = null;
        }
        boolean skillGloballyAvailable = skill != null && Boolean.TRUE.equals(skill.getEnabled());
        if (skillGloballyAvailable) {
            for (AgentEntity agent : allAgents) {
                if (explicitAgentIds.contains(agent.getId())) continue;
                java.util.Set<Long> bound = agentBindingService.getBoundSkillIds(agent.getId());
                // null → no explicit bindings → uses every enabled skill
                if (bound == null) implicitAgentIds.add(agent.getId());
            }
        }

        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        // Stable order: explicit first (most "intentional" relationship),
        // then implicit, agents inside each group keep DB insert order.
        for (Long agentId : explicitAgentIds) {
            appendAgentRow(rows, agentId, "explicit");
        }
        for (Long agentId : implicitAgentIds) {
            appendAgentRow(rows, agentId, "implicit");
        }
        return R.ok(rows);
    }

    private void appendAgentRow(List<Map<String, Object>> rows, Long agentId, String binding) {
        try {
            AgentEntity agent = agentService.getAgent(agentId);
            if (agent == null) return;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", agent.getId());
            row.put("name", agent.getName());
            row.put("icon", agent.getIcon());
            row.put("binding", binding);
            rows.add(row);
        } catch (Exception ignored) {
            // agent may have been deleted; skip rather than fail the whole list
        }
    }

    // ==================== Lessons API (RFC-090 §7 + §11.4) ====================

    /**
     * Read the per-skill {@code LESSONS.md} body for the detail drawer.
     * Returns {@code entries: []} when the file is missing.
     */
    @Operation(summary = "Read per-skill LESSONS.md (RFC-090 §11.4)")
    @GetMapping("/{id}/lessons")
    public R<Map<String, Object>> getLessons(@PathVariable Long id) {
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) return R.fail("Skill not found: " + id);
        String body = lessonsService.readLessons(resolved);
        // entryCount = number of "## " headed sections (RFC-090 §11.4 "💡 N entries" badge).
        int entryCount = 0;
        if (body != null && !body.isBlank()) {
            int from = 0;
            while (true) {
                int idx = body.indexOf("\n## ", from);
                if (idx < 0) break;
                entryCount++;
                from = idx + 1;
            }
            // Also count a leading "## " (no preceding newline) at start of file.
            if (body.startsWith("## ")) entryCount++;
        }
        return R.ok(Map.of(
                "skillId", id,
                "skillName", resolved.getName(),
                "raw", body == null ? "" : body,
                "entryCount", entryCount
        ));
    }

    @Operation(summary = "Clear all lessons for a skill (RFC-090 §11.4)")
    @PostMapping("/{id}/lessons/clear")
    public R<Map<String, Object>> clearLessons(@PathVariable Long id) {
        ResolvedSkill resolved = skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(r -> r != null && id.equals(r.getId()))
                .findFirst()
                .orElse(null);
        if (resolved == null) return R.fail("Skill not found: " + id);
        boolean cleared = lessonsService.clearLessons(resolved);
        return R.ok(Map.of("cleared", cleared));
    }

    // ==================== Synthesis API (RFC-023) ====================

    @Operation(summary = "从对话历史合成 Skill（RFC-023）")
    @PostMapping("/synthesize-from-conversation")
    public R<Map<String, Object>> synthesizeFromConversation(@RequestBody Map<String, Object> body) {
        String conversationId = (String) body.get("conversationId");
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        if (conversationId == null || conversationId.isBlank()) {
            return R.fail("conversationId is required");
        }

        SkillSynthesisService.SynthesisResult result = synthesisService.synthesize(conversationId, agentId);

        if (result.blocked()) {
            return R.ok(Map.of(
                    "success", false,
                    "blocked", true,
                    "skillName", result.skillName() != null ? result.skillName() : "",
                    "error", result.error(),
                    "scanSummary", result.scanSummary() != null ? result.scanSummary() : ""
            ));
        }
        if (!result.success()) {
            return R.ok(Map.of("success", false, "error", result.error()));
        }
        return R.ok(Map.of(
                "success", true,
                "skillId", result.skillId(),
                "skillName", result.skillName()
        ));
    }

    // ==================== Workspace API ====================

    @Operation(summary = "将 skill 导出到工作区目录")
    @PostMapping("/{id}/export-workspace")
    public R<Map<String, Object>> exportToWorkspace(@PathVariable Long id) {
        SkillEntity skill = skillService.getSkill(id);
        var path = workspaceManager.exportToWorkspace(skill.getName(), skill.getSkillContent());
        if (path == null) {
            return R.ok(Map.of("success", false, "message", "Failed to export workspace"));
        }
        return R.ok(Map.of("success", true, "path", path.toString()));
    }

    @Operation(summary = "获取 skill 工作区信息")
    @GetMapping("/{id}/workspace")
    public R<Map<String, Object>> getWorkspaceInfo(@PathVariable Long id) {
        SkillEntity skill = skillService.getSkill(id);
        return R.ok(workspaceManager.getWorkspaceInfo(skill.getName()));
    }
}
