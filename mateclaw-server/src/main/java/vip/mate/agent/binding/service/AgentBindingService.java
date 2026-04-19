package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.agent.binding.model.AgentProviderPreference;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;

import java.util.Collections;
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
@RequiredArgsConstructor
public class AgentBindingService {

    private final AgentSkillBindingMapper skillBindingMapper;
    private final AgentToolBindingMapper toolBindingMapper;
    private final AgentProviderPreferenceMapper providerPreferenceMapper;

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
