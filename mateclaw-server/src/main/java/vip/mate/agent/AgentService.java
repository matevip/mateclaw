package vip.mate.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.memory.service.MemoryRecallTracker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 业务服务
 * <p>
 * 负责 Agent 的 CRUD 管理和运行时实例管理。
 * 构建逻辑委托给 {@link AgentGraphBuilder}。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentGraphBuilder agentGraphBuilder;
    private final MemoryRecallTracker memoryRecallTracker;

    /** 运行时 Agent 实例缓存（agentId -> BaseAgent） */
    private final Map<Long, BaseAgent> agentInstances = new ConcurrentHashMap<>();

    // ==================== CRUD ====================

    public List<AgentEntity> listAgents() {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .orderByDesc(AgentEntity::getCreateTime));
    }

    /**
     * 按工作区列出 Agent
     */
    public List<AgentEntity> listAgentsByWorkspace(Long workspaceId) {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .orderByDesc(AgentEntity::getCreateTime));
    }

    public AgentEntity getAgent(Long id) {
        AgentEntity entity = agentMapper.selectById(id);
        if (entity == null) {
            throw new MateClawException("err.agent.not_found", "Agent不存在: " + id);
        }
        return entity;
    }

    public AgentEntity createAgent(AgentEntity agent) {
        agent.setEnabled(true);
        if (agent.getAgentType() == null) {
            agent.setAgentType("react");
        }
        agentMapper.insert(agent);
        return agent;
    }

    public AgentEntity updateAgent(AgentEntity agent) {
        agentMapper.updateById(agent);
        agentInstances.remove(agent.getId());
        return agent;
    }

    public void deleteAgent(Long id) {
        agentMapper.deleteById(id);
        agentInstances.remove(id);
    }

    /**
     * 清除 Agent 运行时缓存（绑定变更后需调用，使下次对话重新构建 Agent）
     */
    public void invalidateAgentCache(Long agentId) {
        agentInstances.remove(agentId);
    }

    // ==================== 运行时入口 ====================

    public String chat(Long agentId, String message, String conversationId) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);
        return agent.chat(message, conversationId);
    }

    public Flux<String> chatStream(Long agentId, String message, String conversationId) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);
        return agent.chatStream(message, conversationId);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId) {
        return chatStructuredStream(agentId, message, conversationId, "", null);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId) {
        return chatStructuredStream(agentId, message, conversationId, requesterId, null);
    }

    public Flux<StreamDelta> chatStructuredStream(Long agentId, String message, String conversationId,
                                                   String requesterId, String thinkingLevel) {
        memoryRecallTracker.trackRecalls(agentId, message);
        BaseAgent agent = getOrBuildAgent(agentId);

        // 设置请求级思考深度（通过 ThreadLocal 传递到 StateGraph 执行）
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            ThinkingLevelHolder.set(thinkingLevel);
        } else {
            // 尝试从 Agent 默认配置读取
            AgentEntity entity = getAgent(agentId);
            if (entity != null && entity.getDefaultThinkingLevel() != null) {
                ThinkingLevelHolder.set(entity.getDefaultThinkingLevel());
            } else {
                ThinkingLevelHolder.clear();
            }
        }

        if (agent instanceof StructuredStreamCapable capable) {
            return capable.chatStructuredStream(message, conversationId,
                    requesterId != null ? requesterId : "")
                    .doFinally(signal -> ThinkingLevelHolder.clear());
        }

        // 降级：不支持结构化流的 Agent，包装为纯内容流
        ThinkingLevelHolder.clear();
        return agent.chatStream(message, conversationId)
                .map(chunk -> new StreamDelta(chunk, null));
    }

    public String execute(Long agentId, String goal, String conversationId) {
        memoryRecallTracker.trackRecalls(agentId, goal);
        BaseAgent agent = getOrBuildAgent(agentId);
        return agent.execute(goal, conversationId);
    }

    /**
     * 带工具重放的 chat 调用（审批通过后由 ChannelMessageRouter 或 ApprovalController 调用）
     *
     * @param agentId          Agent ID
     * @param userMessage      用户消息（如"继续执行已批准的工具"）
     * @param conversationId   会话 ID
     * @param toolCallPayload  要重放的工具调用 JSON
     * @return Agent 回复
     */
    public String chatWithReplay(Long agentId, String userMessage, String conversationId,
                                  String toolCallPayload) {
        memoryRecallTracker.trackRecalls(agentId, userMessage);
        BaseAgent agent = getOrBuildAgent(agentId);
        return agent.chatWithReplay(userMessage, conversationId, toolCallPayload);
    }

    /**
     * 带工具重放的流式调用（Web 端审批通过后使用，通过 SSE 推送结果）
     */
    public Flux<StreamDelta> chatWithReplayStream(Long agentId, String userMessage, String conversationId,
                                                   String toolCallPayload) {
        return chatWithReplayStream(agentId, userMessage, conversationId, toolCallPayload, "");
    }

    public Flux<StreamDelta> chatWithReplayStream(Long agentId, String userMessage, String conversationId,
                                                   String toolCallPayload, String requesterId) {
        memoryRecallTracker.trackRecalls(agentId, userMessage);
        BaseAgent agent = getOrBuildAgent(agentId);
        return agent.chatWithReplayStream(userMessage, conversationId, toolCallPayload,
                requesterId != null ? requesterId : "");
    }

    public AgentState getAgentState(Long agentId) {
        BaseAgent agent = agentInstances.get(agentId);
        return agent != null ? agent.getState() : AgentState.IDLE;
    }

    // ==================== 缓存管理 ====================

    public void refreshAgent(Long agentId) {
        agentInstances.remove(agentId);
        log.info("Agent instance cache cleared: {}", agentId);
    }

    public void refreshAllAgents() {
        agentInstances.clear();
        log.info("All agent instance caches cleared");
    }

    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        refreshAllAgents();
        log.info("Agent caches refreshed after model config change: {}", event.reason());
    }

    @EventListener
    public void onToolGuardConfigChanged(vip.mate.tool.guard.service.ToolGuardConfigService.ToolGuardConfigChangedEvent event) {
        refreshAllAgents();
        log.info("Agent caches refreshed after tool guard config change (denied tools may have changed)");
    }

    // ==================== 内部方法 ====================

    private BaseAgent getOrBuildAgent(Long agentId) {
        return agentInstances.computeIfAbsent(agentId, id -> {
            AgentEntity entity = getAgent(id);
            if (!Boolean.TRUE.equals(entity.getEnabled())) {
                throw new MateClawException("err.agent.disabled", "Agent 已禁用: " + entity.getName());
            }
            return agentGraphBuilder.build(entity);
        });
    }

    // ==================== StreamDelta ====================

    public record StreamDelta(String content, String thinking, String eventType, Map<String, Object> eventData, boolean persistenceOnly) {

        // 兼容构造器（广播+持久化）
        public StreamDelta(String content, String thinking) {
            this(content, thinking, null, null, false);
        }

        /** 仅用于持久化，不再广播（内容已由 NodeStreamingChatHelper 实时广播过） */
        public static StreamDelta persistOnly(String content, String thinking) {
            return new StreamDelta(content, thinking, null, null, true);
        }

        public static StreamDelta empty() {
            return new StreamDelta(null, null, null, null, false);
        }

        public static StreamDelta event(String type, Map<String, Object> data) {
            return new StreamDelta(null, null, type, data, false);
        }

        public boolean isEvent() {
            return eventType != null;
        }

        public boolean hasPayload() {
            return StringUtils.hasText(content) || StringUtils.hasText(thinking);
        }

        public int contentLength() {
            return content != null ? content.length() : 0;
        }

        public int thinkingLength() {
            return thinking != null ? thinking.length() : 0;
        }
    }
}
