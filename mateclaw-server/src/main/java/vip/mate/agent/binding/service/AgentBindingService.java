package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.agent.binding.model.AgentProviderPreference;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.skill.runtime.model.ResolvedSkill;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 能力绑定服务
 * <p>
 * 管理 Agent 与 Skill/Tool 的关联关系。
 * 当 Agent 没有任何绑定记录时，默认使用全局 enabled 的 tool/skill（向后兼容）。
 * 一旦有绑定记录，则严格按绑定列表过滤。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class AgentBindingService {

    private final AgentSkillBindingMapper skillBindingMapper;
    private final AgentToolBindingMapper toolBindingMapper;
    private final AgentProviderPreferenceMapper providerPreferenceMapper;
    /**
     * {@code @Lazy} — SkillRuntimeService and AgentBindingService both sit
     * near the agent boot path; the lazy proxy avoids a circular bean
     * graph when SkillRuntimeService initializes after binding.
     */
    private final SkillRuntimeService skillRuntimeService;

    @Autowired
    public AgentBindingService(AgentSkillBindingMapper skillBindingMapper,
                               AgentToolBindingMapper toolBindingMapper,
                               AgentProviderPreferenceMapper providerPreferenceMapper,
                               @Lazy SkillRuntimeService skillRuntimeService) {
        this.skillBindingMapper = skillBindingMapper;
        this.toolBindingMapper = toolBindingMapper;
        this.providerPreferenceMapper = providerPreferenceMapper;
        this.skillRuntimeService = skillRuntimeService;
    }

    // ==================== Skill Bindings ====================

    public List<AgentSkillBinding> listSkillBindings(Long agentId) {
        return skillBindingMapper.selectList(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .orderByAsc(AgentSkillBinding::getCreateTime));
    }

    /**
     * 获取 Agent 绑定的 enabled skill ID 集合。
     * 返回 null 表示该 agent 没有自定义绑定（使用全局默认）。
     */
    public Set<Long> getBoundSkillIds(Long agentId) {
        List<AgentSkillBinding> bindings = listSkillBindings(agentId);
        if (bindings.isEmpty()) {
            return null; // 无绑定 → 全局默认
        }
        return bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AgentSkillBinding::getSkillId)
                .collect(Collectors.toSet());
    }

    public AgentSkillBinding bindSkill(Long agentId, Long skillId) {
        // 检查是否已绑定
        AgentSkillBinding existing = skillBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .eq(AgentSkillBinding::getSkillId, skillId));
        if (existing != null) {
            existing.setEnabled(true);
            skillBindingMapper.updateById(existing);
            return existing;
        }
        AgentSkillBinding binding = new AgentSkillBinding();
        binding.setAgentId(agentId);
        binding.setSkillId(skillId);
        binding.setEnabled(true);
        skillBindingMapper.insert(binding);
        return binding;
    }

    public void unbindSkill(Long agentId, Long skillId) {
        skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId)
                        .eq(AgentSkillBinding::getSkillId, skillId));
    }

    /**
     * 批量设置 Agent 的 skill 绑定（替换模式）
     */
    public void setSkillBindings(Long agentId, List<Long> skillIds) {
        // 删除旧绑定
        skillBindingMapper.delete(
                new LambdaQueryWrapper<AgentSkillBinding>()
                        .eq(AgentSkillBinding::getAgentId, agentId));
        // 创建新绑定
        if (skillIds != null) {
            for (Long skillId : skillIds) {
                AgentSkillBinding binding = new AgentSkillBinding();
                binding.setAgentId(agentId);
                binding.setSkillId(skillId);
                binding.setEnabled(true);
                skillBindingMapper.insert(binding);
            }
        }
    }

    // ==================== Tool Bindings ====================

    public List<AgentToolBinding> listToolBindings(Long agentId) {
        return toolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .orderByAsc(AgentToolBinding::getCreateTime));
    }

    /**
     * 获取 Agent 绑定的 enabled tool name 集合。
     * 返回 null 表示该 agent 没有自定义绑定（使用全局默认）。
     */
    public Set<String> getBoundToolNames(Long agentId) {
        List<AgentToolBinding> bindings = listToolBindings(agentId);
        if (bindings.isEmpty()) {
            return null; // 无绑定 → 全局默认
        }
        return bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AgentToolBinding::getToolName)
                .collect(Collectors.toSet());
    }

    /**
     * RFC-090 §14.2 — single entry point that maps an agent's bindings to
     * the set of tool names allowed at runtime.
     *
     * <p>Three-state semantics (mirrors {@link #getBoundSkillIds} /
     * {@link #getBoundToolNames}):
     * <ul>
     *   <li><b>{@code null} bound skills + {@code null} bound tools</b> →
     *       returns {@code null}. Caller treats this as "no agent-level
     *       restriction; let the upstream {@code ToolSet} pass through
     *       its global default".</li>
     *   <li><b>at least one side non-null</b> → returns the union, which
     *       may be empty (= "this agent is explicitly restricted to no
     *       tools"). The caller must distinguish empty from null.</li>
     * </ul>
     *
     * <p>Skill expansion rules (§14.2):
     * <ul>
     *   <li>Resolved skill found → contribute
     *       {@code ResolvedSkill.getEffectiveAllowedTools()} — only tools
     *       whose owning feature is READY (unavailable features stay
     *       hidden from the LLM, §10.2 Q8).</li>
     *   <li>Skill bound but unresolved (e.g. legacy or missing manifest)
     *       → contribute nothing through this path; legacy SKILL.md prompt
     *       enhancement still runs separately.</li>
     * </ul>
     */
    public Set<String> getEffectiveToolNames(Long agentId) {
        Set<Long> boundSkillIds = getBoundSkillIds(agentId);
        Set<String> directTools = getBoundToolNames(agentId);

        // (1) null + null → no restriction; defer to the global default.
        if (boundSkillIds == null && directTools == null) {
            return null;
        }

        Set<String> merged = new LinkedHashSet<>();

        if (boundSkillIds != null) {
            for (Long skillId : boundSkillIds) {
                ResolvedSkill resolved = findResolvedSkillById(skillId);
                if (resolved == null) continue;
                if (!vip.mate.skill.runtime.SkillRuntimeService.passesActiveGate(resolved)) {
                    // §14.2 fix: a disabled / security-blocked / setup-needed
                    // skill must not contribute tools to the LLM
                    // advertisement even if it's still bound. Without this
                    // guard, users see ghost tools for skills they thought
                    // were off.
                    continue;
                }
                Set<String> skillTools = resolved.getEffectiveAllowedTools();
                if (skillTools != null && !skillTools.isEmpty()) merged.addAll(skillTools);
            }
        }

        if (directTools != null) {
            // ∪ Advanced 直选的原子 tool（§9.2 调整 B）
            merged.addAll(directTools);
        }

        // System-level tools that don't belong to any single skill but
        // are agent-wide capabilities. Without this carve-out, binding
        // any skill silently strips record_lesson / remember / structured-
        // memory tools, breaking the §11 self-evolution loop entirely
        // (the LLM stops being able to write to LESSONS.md / MEMORY.md).
        merged.addAll(SYSTEM_LEVEL_TOOLS);

        return merged;
    }

    /**
     * RFC-090 §11 — tools that exist outside the skill scope and must
     * survive any agent-level skill binding restriction.
     *
     * <p>Add new entries here only after verifying the tool is genuinely
     * agent-wide, not skill-specific. Tools added here bypass the
     * {@link #getEffectiveToolNames} allowlist completely.
     */
    private static final Set<String> SYSTEM_LEVEL_TOOLS = Set.of(
            // Memory write/read primitives — every agent needs these
            // regardless of skill bindings, otherwise the self-evolution
            // path collapses (§11.3 / §11.4).
            "record_lesson",
            "remember",
            "remember_structured",
            "recall_structured",
            "forget_structured",
            // Workspace memory file CRUD (PROFILE.md / MEMORY.md / SOUL.md)
            "read_workspace_file",
            "write_workspace_file",
            "list_workspace_files",
            // Skill discovery / dispatch — skills are docs, not callables;
            // these helpers let the LLM read SKILL.md / run scripts.
            "readSkillFile",
            "runSkillScript",
            // Date/time + delegate — fundamental cross-skill utilities
            "datetime",
            "delegate_agent",
            // Document / media generation — agent-wide capabilities, never
            // declared inside any skill manifest. Pre-Phase-2b these were
            // universally visible; the new gate silently strips them whenever
            // any skill is bound, breaking "generate a Word doc / image /
            // song / video" intents on agents that happen to have a skill
            // on. Regression observed 2026-05-01: a Code Reviewer agent with
            // skills bound dropped renderDocx and fell back to dumping the
            // markdown body for the user to copy.
            "renderDocx",
            "renderDocxFromFile",
            "renderDocxFromFiles",
            "image_generate",
            "music_generate",
            "video_generate"
    );

    private ResolvedSkill findResolvedSkillById(Long skillId) {
        if (skillId == null || skillRuntimeService == null) return null;
        // resolveAllSkillsStatus returns every skill in the catalog, not
        // just the active ones, so we still see READY/SETUP_NEEDED status
        // for bound but partially-unsatisfied skills.
        return skillRuntimeService.resolveAllSkillsStatus().stream()
                .filter(s -> s != null && skillId.equals(s.getId()))
                .findFirst()
                .orElse(null);
    }

    public AgentToolBinding bindTool(Long agentId, String toolName) {
        AgentToolBinding existing = toolBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getToolName, toolName));
        if (existing != null) {
            existing.setEnabled(true);
            toolBindingMapper.updateById(existing);
            return existing;
        }
        AgentToolBinding binding = new AgentToolBinding();
        binding.setAgentId(agentId);
        binding.setToolName(toolName);
        binding.setEnabled(true);
        toolBindingMapper.insert(binding);
        return binding;
    }

    public void unbindTool(Long agentId, String toolName) {
        toolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getToolName, toolName));
    }

    /**
     * 批量设置 Agent 的 tool 绑定（替换模式）
     */
    public void setToolBindings(Long agentId, List<String> toolNames) {
        toolBindingMapper.delete(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getAgentId, agentId));
        if (toolNames != null) {
            for (String toolName : toolNames) {
                AgentToolBinding binding = new AgentToolBinding();
                binding.setAgentId(agentId);
                binding.setToolName(toolName);
                binding.setEnabled(true);
                toolBindingMapper.insert(binding);
            }
        }
    }

    // ==================== Provider Preferences (RFC-009 PR-3) ====================

    /** Raw rows for the agent edit form. Sorted by sort_order ascending. */
    public List<AgentProviderPreference> listProviderPreferences(Long agentId) {
        return providerPreferenceMapper.selectList(
                new LambdaQueryWrapper<AgentProviderPreference>()
                        .eq(AgentProviderPreference::getAgentId, agentId)
                        .orderByAsc(AgentProviderPreference::getSortOrder));
    }

    /**
     * Ordered list of provider ids the agent prefers, lowest sort_order
     * first. Disabled rows are filtered out. Empty list means "no
     * preference — fall back to the global chain order".
     *
     * <p>Used by {@code AgentGraphBuilder.buildFallbackChain} to bias the
     * fallback chain order per agent.</p>
     */
    public List<String> getPreferredProviderIds(Long agentId) {
        if (agentId == null) return Collections.emptyList();
        return listProviderPreferences(agentId).stream()
                .filter(p -> Boolean.TRUE.equals(p.getEnabled()))
                .map(AgentProviderPreference::getProviderId)
                .collect(Collectors.toList());
    }

    /**
     * Replace the full preference list for an agent. {@code providerIds}
     * is the new ordered preference (index 0 = highest preference).
     * Empty / null list clears all preferences for the agent.
     */
    public void setProviderPreferences(Long agentId, List<String> providerIds) {
        providerPreferenceMapper.delete(
                new LambdaQueryWrapper<AgentProviderPreference>()
                        .eq(AgentProviderPreference::getAgentId, agentId));
        if (providerIds == null) return;
        int order = 0;
        for (String providerId : providerIds) {
            if (providerId == null || providerId.isBlank()) continue;
            AgentProviderPreference row = new AgentProviderPreference();
            row.setAgentId(agentId);
            row.setProviderId(providerId.trim());
            row.setSortOrder(order++);
            row.setEnabled(true);
            providerPreferenceMapper.insert(row);
        }
    }
}
