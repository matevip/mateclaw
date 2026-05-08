package vip.mate.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;

/**
 * Production binding for {@link AgentInvoker}. Looks agents up by name within
 * the workspace via {@link AgentMapper} and delegates execution to
 * {@link AgentService#chat(Long, String, String)}. The conversation id is
 * passed through as-is — the runner is responsible for generating an ephemeral
 * id per step so multi-step runs do not collide on conversation history.
 */
@Component
public class DefaultAgentInvoker implements AgentInvoker {

    private final AgentService agentService;
    private final AgentMapper agentMapper;

    public DefaultAgentInvoker(AgentService agentService, AgentMapper agentMapper) {
        this.agentService = agentService;
        this.agentMapper = agentMapper;
    }

    @Override
    public String invoke(long agentId, String prompt, String conversationId) {
        return agentService.chat(agentId, prompt, conversationId);
    }

    @Override
    public Long resolveAgentId(long workspaceId, String agentName) {
        if (agentName == null || agentName.isBlank()) return null;
        // Workspace-scoped only — no fallback to a global lookup. The
        // earlier "fall back to workspace-agnostic" branch let an old
        // revision (or any code path that bypassed publish-time ACL)
        // pull a same-named agent from a different workspace at runtime,
        // which is exactly what tenant isolation forbids. The
        // publish-time ACL layer is also workspace-scoped, so a draft
        // referencing a foreign agent is rejected before it ever runs.
        AgentEntity entity = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getName, agentName.trim())
                .eq(AgentEntity::getEnabled, true));
        return entity == null ? null : entity.getId();
    }
}
