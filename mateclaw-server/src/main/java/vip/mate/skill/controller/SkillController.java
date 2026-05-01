package vip.mate.skill.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
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

    @Operation(summary = "删除技能")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        skillService.deleteSkill(id);
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
