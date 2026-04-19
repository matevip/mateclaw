package vip.mate.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import vip.mate.agent.ThinkingLevelHolder;
import vip.mate.agent.graph.StateGraphReActAgent;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.edge.ObservationDispatcher;
import vip.mate.agent.graph.edge.ReasoningDispatcher;
import vip.mate.agent.graph.lifecycle.ReActLifecycleListener;
import vip.mate.agent.graph.node.*;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.agent.graph.plan.StateGraphPlanExecuteAgent;
import vip.mate.agent.graph.plan.edge.PlanGenerationDispatcher;
import vip.mate.agent.graph.plan.edge.StepProgressDispatcher;
import vip.mate.agent.graph.plan.node.*;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.config.GraphObservationProperties;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.planning.service.PlanningService;
import vip.mate.skill.service.SkillService;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.ToolRegistry;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.tool.guard.service.ToolGuardService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.wiki.service.WikiContextService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 图构建器
 * <p>
 * 纯构建器，不做执行。从 AgentService 中提取出所有 Agent 实例构建逻辑，
 * 包括模型创建、图编译、prompt 增强等。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGraphBuilder {

    private final ToolRegistry toolRegistry;
    private final AgentBindingService agentBindingService;
    private final SkillService skillService;
    private final vip.mate.skill.runtime.SkillRuntimeService skillRuntimeService;
    private final ConversationService conversationService;
    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;
    private final PlanningService planningService;
    private final ToolGuardService toolGuardService;
    private final vip.mate.tool.guard.service.ToolGuardConfigService toolGuardConfigService;
    private final ApprovalWorkflowService approvalService;
    private final ChatStreamTracker streamTracker;
    private final SystemSettingService systemSettingService;
    private final DashScopeChatModel dashScopeChatModel;
    private final DashScopeConnectionProperties dashScopeConnectionProperties;
    private final RetryTemplate retryTemplate;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectMapper objectMapper;
    private final GraphObservationProperties graphObservationProperties;
    private final vip.mate.config.ToolTimeoutProperties toolTimeoutProperties;
    private final MemoryManager memoryManager;
    private final WorkspaceFileService workspaceFileService;
    private final vip.mate.agent.context.ConversationWindowManager conversationWindowManager;
    private final vip.mate.llm.chatgpt.ChatGPTResponsesClient chatGPTResponsesClient;
    private final WikiContextService wikiContextService;
    private final vip.mate.workspace.core.service.WorkspaceService workspaceService;
    private final vip.mate.llm.cache.AnthropicCacheOptionsFactory anthropicCacheOptionsFactory;
    private final vip.mate.llm.cache.LlmCacheMetricsAggregator llmCacheMetricsAggregator;
    private final vip.mate.agent.graph.executor.ToolResultStorage toolResultStorage;
    private final vip.mate.tool.ToolConcurrencyRegistry toolConcurrencyRegistry;
    private final vip.mate.i18n.I18nService i18nService;
    private final vip.mate.llm.failover.ProviderHealthTracker providerHealthTracker;

    /**
     * 根据 AgentEntity 构建完整的 Agent 实例
     */
    public BaseAgent build(AgentEntity entity) {
        AgentToolSet toolSet = toolRegistry.getEnabledToolSet();

        // 过滤掉 denied 工具，使模型完全看不到它们（防止 prompt injection 利用 schema）
        toolSet = toolSet.withDeniedToolsFiltered(toolGuardConfigService.getDeniedTools());

        // Per-agent tool 绑定过滤：如果 agent 有自定义 tool 绑定，则只保留绑定的工具
        Set<String> boundTools = agentBindingService.getBoundToolNames(entity.getId());
        toolSet = toolSet.withAllowedToolsOnly(boundTools); // null = 全局默认

        // 统一使用全局默认模型（AgentEntity.modelName 为历史残留字段，不参与运行时选择）
        ModelConfigEntity runtimeModel;
        try {
            runtimeModel = modelConfigService.getDefaultModel();
        } catch (Exception e) {
            throw new MateClawException("err.agent.no_default_model", "无法构建 Agent：请先在「设置 → 模型」中配置并启用默认模型");
        }

        ModelProviderEntity provider;
        try {
            provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        } catch (Exception e) {
            throw new MateClawException("err.agent.model_not_configured", "模型 " + runtimeModel.getModelName()
                    + " 的 Provider（" + runtimeModel.getProvider() + "）未配置，请检查模型设置");
        }
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());

        // 内置搜索检测（DashScope / Kimi），但不再移除 WebSearchTool — 两者协同而非互斥
        boolean builtinSearchEnabled = false;
        Map<String, Object> providerKwargs = modelProviderService.readProviderGenerateKwargs(provider);
        if (protocol == ModelProtocol.DASHSCOPE_NATIVE) {
            builtinSearchEnabled = isDashScopeSearchEnabled(runtimeModel, provider);
        } else if (isKimiProvider(provider) && Boolean.TRUE.equals(providerKwargs.get("enableSearch"))) {
            builtinSearchEnabled = true;
        }
        if (builtinSearchEnabled) {
            // Phase 2: 不再移除 search 工具，改为在 prompt 中设定优先级引导
            // 内置搜索作为首选，search 工具作为补充/兜底
            log.info("内置搜索已开启 (provider={})，search 工具保留作为补充通道", provider.getProviderId());
        }
        int maxIter = entity.getMaxIterations() != null ? entity.getMaxIterations() : 25;

        String enhancedPrompt = buildEnhancedPrompt(entity, builtinSearchEnabled);

        // 当前仅支持 DashScope 和 OpenAI-compatible，其他协议直接拒绝
        if (!supportsStateGraph(protocol)) {
            throw new MateClawException("err.agent.protocol_not_supported", "当前不支持协议 " + protocol.getId()
                    + "，请切换到 DashScope 或 OpenAI-compatible 模型");
        }

        BaseAgent agent;
        boolean toolCallingEnabled;
        if ("plan_execute".equals(entity.getAgentType())) {
            agent = buildPlanExecuteAgent(toolSet, runtimeModel, maxIter);
            toolCallingEnabled = true;
            log.info("Built StateGraph Plan-Execute agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        } else {
            agent = buildReActAgent(toolSet, runtimeModel, maxIter);
            // StateGraph 路径下工具调用由 ActionNode 控制，始终启用
            toolCallingEnabled = true;
            log.info("Built StateGraph ReAct agent: {} (maxIterations={}, tools={}, protocol={})",
                    entity.getName(), maxIter, toolSet.size(), protocol.getId());
        }

        // 设置通用属性
        agent.agentId = String.valueOf(entity.getId());
        agent.agentName = entity.getName();
        agent.systemPrompt = enhancedPrompt;
        agent.maxIterations = maxIter;
        agent.modelName = runtimeModel.getModelName();
        agent.runtimeProviderId = provider != null ? provider.getProviderId() : "";
        agent.temperature = runtimeModel.getTemperature();
        agent.maxTokens = runtimeModel.getMaxTokens();
        agent.maxInputTokens = runtimeModel.getMaxInputTokens();
        agent.topP = runtimeModel.getTopP();
        agent.toolCallingEnabled = toolCallingEnabled;

        // 查找工作区活动目录
        if (entity.getWorkspaceId() != null) {
            try {
                var workspace = workspaceService.getById(entity.getWorkspaceId());
                if (workspace != null && workspace.getBasePath() != null && !workspace.getBasePath().isBlank()) {
                    agent.workspaceBasePath = workspace.getBasePath();
                    log.info("Agent {} bound to workspace basePath: {}", entity.getName(), agent.workspaceBasePath);
                }
            } catch (Exception e) {
                log.warn("Failed to lookup workspace basePath for agent {}: {}", entity.getName(), e.getMessage());
            }
        }

        log.info("Built agent instance: {} (type={}, protocol={}, tools={}, toolCallingEnabled={})",
                entity.getName(), entity.getAgentType(), protocol.getId(),
                toolSet.size(), agent.toolCallingEnabled);
        return agent;
    }

    // ==================== Agent 构建方法 ====================

    StateGraphReActAgent buildReActAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel, int maxIter) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph compiledGraph = buildReActGraph(toolSet, chatModel, maxIter, reasoningEffort, runtimeModel);
        return new StateGraphReActAgent(chatClient, conversationService, compiledGraph,
                chatModel, conversationWindowManager);
    }

    StateGraphPlanExecuteAgent buildPlanExecuteAgent(AgentToolSet toolSet, ModelConfigEntity runtimeModel, int maxIter) {
        ChatModel chatModel = buildRuntimeChatModel(runtimeModel);
        ChatClient chatClient = ChatClient.create(chatModel);
        String reasoningEffort = resolveReasoningEffortForModel(runtimeModel);
        CompiledGraph graph = buildPlanExecuteGraph(toolSet, chatModel, maxIter, reasoningEffort, runtimeModel);
        return new StateGraphPlanExecuteAgent(chatClient, conversationService, graph, planningService,
                chatModel, conversationWindowManager);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations, String reasoningEffort) {
        return buildPlanExecuteGraph(toolSet, chatModel, maxIterations, reasoningEffort, null);
    }

    CompiledGraph buildPlanExecuteGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                         String reasoningEffort, ModelConfigEntity primaryModelConfig) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, toolGuardService, approvalService, streamTracker, toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry);
            PlanGenerationNode planGenerationNode = new PlanGenerationNode(chatModel, planningService, streamingHelper, conversationWindowManager, toolSet);
            StepExecutionNode stepExecutionNode = new StepExecutionNode(chatModel, toolSet, executor, planningService, streamTracker, reasoningEffort, streamingHelper, conversationWindowManager);
            PlanSummaryNode planSummaryNode = new PlanSummaryNode(chatModel, planningService, streamingHelper);
            DirectAnswerNode directAnswerNode = new DirectAnswerNode();

            KeyStrategyFactory keyStrategyFactory = KeyStrategy.builder()
                    // 共享键
                    .addStrategy(MateClawStateKeys.PENDING_EVENTS, KeyStrategy.APPEND)
                    .addStrategy(MateClawStateKeys.CURRENT_PHASE, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SYSTEM_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CONVERSATION_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TRACE_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.AGENT_ID, KeyStrategy.REPLACE)
                    // 会话消息（复用 ReAct 的 MESSAGES key，APPEND 策略）
                    .addStrategy(MateClawStateKeys.MESSAGES, KeyStrategy.APPEND)
                    // Plan 特有键
                    .addStrategy(PlanStateKeys.GOAL, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_ID, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_STEPS, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.PLAN_VALID, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.NEEDS_PLANNING, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_INDEX, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_TITLE, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_RESULT, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.COMPLETED_RESULTS, KeyStrategy.APPEND)
                    .addStrategy(PlanStateKeys.FINAL_SUMMARY, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.DIRECT_ANSWER, KeyStrategy.REPLACE)
                    // 工作上下文（REPLACE 策略，每次重新生成）
                    .addStrategy(PlanStateKeys.WORKING_CONTEXT, KeyStrategy.REPLACE)
                    // Thinking 键
                    .addStrategy(PlanStateKeys.FINAL_SUMMARY_THINKING, KeyStrategy.REPLACE)
                    .addStrategy(PlanStateKeys.CURRENT_STEP_THINKING, KeyStrategy.REPLACE)
                    // 流式防重键
                    .addStrategy(MateClawStateKeys.CONTENT_STREAMED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.THINKING_STREAMED, KeyStrategy.REPLACE)
                    // 流式内容暂存（AWAITING_APPROVAL 路径持久化使用）
                    .addStrategy(MateClawStateKeys.STREAMED_CONTENT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STREAMED_THINKING, KeyStrategy.REPLACE)
                    // 请求者身份（审批身份校验使用）
                    .addStrategy(MateClawStateKeys.REQUESTER_ID, KeyStrategy.REPLACE)
                    // 审批重放键
                    .addStrategy(MateClawStateKeys.FORCED_TOOL_CALL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.PRE_APPROVED_TOOL_CALL, KeyStrategy.REPLACE)
                    // Token Usage
                    .addStrategy(MateClawStateKeys.PROMPT_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.COMPLETION_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_MODEL_NAME, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_PROVIDER_ID, KeyStrategy.REPLACE)
                    .build();

            // Graph 拓扑：
            // START → PLAN_GENERATION → (PlanGenerationDispatcher)
            //   ├→ DIRECT_ANSWER_NODE → END
            //   └→ STEP_EXECUTION → (StepProgressDispatcher)
            //       ├→ STEP_EXECUTION (loop)
            //       └→ PLAN_SUMMARY → END

            StateGraph graph = new StateGraph("plan-execute-agent", keyStrategyFactory)
                    .addNode(PlanStateKeys.PLAN_GENERATION_NODE,
                            AsyncNodeAction.node_async(planGenerationNode))
                    .addNode(PlanStateKeys.STEP_EXECUTION_NODE,
                            AsyncNodeAction.node_async(stepExecutionNode))
                    .addNode(PlanStateKeys.PLAN_SUMMARY_NODE,
                            AsyncNodeAction.node_async(planSummaryNode))
                    .addNode(PlanStateKeys.DIRECT_ANSWER_NODE,
                            AsyncNodeAction.node_async(directAnswerNode))
                    .addEdge(StateGraph.START, PlanStateKeys.PLAN_GENERATION_NODE)
                    .addConditionalEdges(PlanStateKeys.PLAN_GENERATION_NODE,
                            AsyncEdgeAction.edge_async(new PlanGenerationDispatcher()),
                            Map.of(
                                    PlanStateKeys.STEP_EXECUTION_NODE, PlanStateKeys.STEP_EXECUTION_NODE,
                                    PlanStateKeys.DIRECT_ANSWER_NODE, PlanStateKeys.DIRECT_ANSWER_NODE))
                    .addConditionalEdges(PlanStateKeys.STEP_EXECUTION_NODE,
                            AsyncEdgeAction.edge_async(new StepProgressDispatcher()),
                            Map.of(
                                    PlanStateKeys.STEP_EXECUTION_NODE, PlanStateKeys.STEP_EXECUTION_NODE,
                                    PlanStateKeys.PLAN_SUMMARY_NODE, PlanStateKeys.PLAN_SUMMARY_NODE,
                                    StateGraph.END, StateGraph.END))
                    .addEdge(PlanStateKeys.PLAN_SUMMARY_NODE, StateGraph.END)
                    .addEdge(PlanStateKeys.DIRECT_ANSWER_NODE, StateGraph.END);

            return graph.compile(CompileConfig.builder()
                    .recursionLimit(maxIterations > 0 ? maxIterations * 3 + 10 : 300)
                    .build());
        } catch (Exception e) {
            throw new MateClawException("err.agent.plan_compile_failed", "Plan-Execute StateGraph 编译失败: " + e.getMessage());
        }
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations, String reasoningEffort) {
        return buildReActGraph(toolSet, chatModel, maxIterations, reasoningEffort, null);
    }

    CompiledGraph buildReActGraph(AgentToolSet toolSet, ChatModel chatModel, int maxIterations,
                                   String reasoningEffort, ModelConfigEntity primaryModelConfig) {
        try {
            List<vip.mate.llm.failover.FallbackEntry> fallbackChain = buildFallbackChain(primaryModelConfig);
            NodeStreamingChatHelper streamingHelper = new NodeStreamingChatHelper(streamTracker, fallbackChain, llmCacheMetricsAggregator, providerHealthTracker);
            ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, toolGuardService, approvalService, streamTracker, toolTimeoutProperties, toolResultStorage, toolConcurrencyRegistry);
            ReasoningNode reasoningNode = new ReasoningNode(chatModel, toolSet, reasoningEffort, streamingHelper, conversationWindowManager, streamTracker, 0, wikiContextService);
            ActionNode actionNode = new ActionNode(executor, streamTracker);
            ObservationProcessor observationProcessor = new ObservationProcessor(graphObservationProperties);
            ObservationNode observationNode = new ObservationNode(observationProcessor, streamTracker);
            SummarizingNode summarizingNode = new SummarizingNode(chatModel, streamingHelper, streamTracker);
            LimitExceededNode limitExceededNode = new LimitExceededNode(chatModel, observationProcessor, streamingHelper, i18nService);
            FinalAnswerNode finalAnswerNode = new FinalAnswerNode();

            KeyStrategyFactory keyStrategyFactory = KeyStrategy.builder()
                    // 输入字段
                    .addStrategy(MateClawStateKeys.USER_MESSAGE, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CONVERSATION_ID, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SYSTEM_PROMPT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.AGENT_ID, KeyStrategy.REPLACE)
                    // 消息列表（追加策略）
                    .addStrategy(MateClawStateKeys.MESSAGES, KeyStrategy.APPEND)
                    // 迭代控制
                    .addStrategy(MateClawStateKeys.CURRENT_ITERATION, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.MAX_ITERATIONS, KeyStrategy.REPLACE)
                    // 工具调用
                    .addStrategy(MateClawStateKeys.TOOL_CALLS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TOOL_RESULTS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TOOL_CALL_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.LLM_CALL_COUNT, KeyStrategy.REPLACE)
                    // 控制流
                    .addStrategy(MateClawStateKeys.FINAL_ANSWER, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.NEEDS_TOOL_CALL, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.ERROR, KeyStrategy.REPLACE)
                    // 观察历史（REPLACE 策略，由 ObservationNode 手动累加，SummarizingNode 可清空）
                    .addStrategy(MateClawStateKeys.OBSERVATION_HISTORY, KeyStrategy.REPLACE)
                    // Summarizing
                    .addStrategy(MateClawStateKeys.SUMMARIZED_CONTEXT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.FINAL_ANSWER_DRAFT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.SHOULD_SUMMARIZE, KeyStrategy.REPLACE)
                    // 终止控制
                    .addStrategy(MateClawStateKeys.FINISH_REASON, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.LIMIT_EXCEEDED, KeyStrategy.REPLACE)
                    // 统计与追踪
                    .addStrategy(MateClawStateKeys.ERROR_COUNT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.TRACE_ID, KeyStrategy.REPLACE)
                    // 事件流
                    .addStrategy(MateClawStateKeys.PENDING_EVENTS, KeyStrategy.APPEND)
                    .addStrategy(MateClawStateKeys.CURRENT_PHASE, KeyStrategy.REPLACE)
                    // Thinking
                    .addStrategy(MateClawStateKeys.FINAL_THINKING, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.CURRENT_THINKING, KeyStrategy.REPLACE)
                    // 流式防重
                    .addStrategy(MateClawStateKeys.CONTENT_STREAMED, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.THINKING_STREAMED, KeyStrategy.REPLACE)
                    // 审批控制
                    .addStrategy(MateClawStateKeys.AWAITING_APPROVAL, KeyStrategy.REPLACE)
                    // 流式内容暂存（AWAITING_APPROVAL 路径持久化使用）
                    .addStrategy(MateClawStateKeys.STREAMED_CONTENT, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.STREAMED_THINKING, KeyStrategy.REPLACE)
                    // 请求者身份（审批身份校验使用）
                    .addStrategy(MateClawStateKeys.REQUESTER_ID, KeyStrategy.REPLACE)
                    // 审批重放
                    .addStrategy(MateClawStateKeys.FORCED_TOOL_CALL, KeyStrategy.REPLACE)
                    // Token Usage
                    .addStrategy(MateClawStateKeys.PROMPT_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.COMPLETION_TOKENS, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_MODEL_NAME, KeyStrategy.REPLACE)
                    .addStrategy(MateClawStateKeys.RUNTIME_PROVIDER_ID, KeyStrategy.REPLACE)
                    .build();

            StateGraph graph = new StateGraph("react-agent-v2", keyStrategyFactory)
                    .addNode(MateClawStateKeys.REASONING_NODE,
                            AsyncNodeAction.node_async(reasoningNode))
                    .addNode(MateClawStateKeys.ACTION_NODE,
                            AsyncNodeAction.node_async(actionNode))
                    .addNode(MateClawStateKeys.OBSERVATION_NODE,
                            AsyncNodeAction.node_async(observationNode))
                    .addNode(MateClawStateKeys.SUMMARIZING_NODE,
                            AsyncNodeAction.node_async(summarizingNode))
                    .addNode(MateClawStateKeys.LIMIT_EXCEEDED_NODE,
                            AsyncNodeAction.node_async(limitExceededNode))
                    .addNode(MateClawStateKeys.FINAL_ANSWER_NODE,
                            AsyncNodeAction.node_async(finalAnswerNode))
                    .addEdge(StateGraph.START, MateClawStateKeys.REASONING_NODE)
                    .addConditionalEdges(MateClawStateKeys.REASONING_NODE,
                            AsyncEdgeAction.edge_async(new ReasoningDispatcher()),
                            Map.of(MateClawStateKeys.ACTION_NODE, MateClawStateKeys.ACTION_NODE,
                                    MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.SUMMARIZING_NODE,
                                    MateClawStateKeys.FINAL_ANSWER_NODE, MateClawStateKeys.FINAL_ANSWER_NODE,
                                    MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.LIMIT_EXCEEDED_NODE))
                    .addEdge(MateClawStateKeys.ACTION_NODE, MateClawStateKeys.OBSERVATION_NODE)
                    .addConditionalEdges(MateClawStateKeys.OBSERVATION_NODE,
                            AsyncEdgeAction.edge_async(new ObservationDispatcher()),
                            Map.of(MateClawStateKeys.REASONING_NODE, MateClawStateKeys.REASONING_NODE,
                                    MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.SUMMARIZING_NODE,
                                    MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.LIMIT_EXCEEDED_NODE,
                                    MateClawStateKeys.FINAL_ANSWER_NODE, MateClawStateKeys.FINAL_ANSWER_NODE))
                    .addEdge(MateClawStateKeys.SUMMARIZING_NODE, MateClawStateKeys.REASONING_NODE)
                    .addEdge(MateClawStateKeys.LIMIT_EXCEEDED_NODE, MateClawStateKeys.FINAL_ANSWER_NODE)
                    .addEdge(MateClawStateKeys.FINAL_ANSWER_NODE, StateGraph.END);

            return graph.compile(CompileConfig.builder()
                    .recursionLimit(maxIterations > 0 ? maxIterations * 3 + 10 : 300)
                    .withLifecycleListener(new ReActLifecycleListener())
                    .build());
        } catch (Exception e) {
            throw new MateClawException("err.agent.graph_compile_failed", "StateGraph v2 编译失败: " + e.getMessage());
        }
    }

    // ==================== 协议能力判断 ====================

    private boolean supportsStateGraph(ModelProtocol protocol) {
        return protocol == ModelProtocol.DASHSCOPE_NATIVE
                || protocol == ModelProtocol.OPENAI_COMPATIBLE
                || protocol == ModelProtocol.ANTHROPIC_MESSAGES
                || protocol == ModelProtocol.OPENAI_CHATGPT;
    }

    // ==================== 模型构建 ====================

    /**
     * 构建运行时 ChatModel（不包装为 ChatClient）
     * 用于 StateGraph 节点直接调用。使用注入的共享 {@link #retryTemplate} 作为 Spring AI
     * 内层重试策略。
     */
    public ChatModel buildRuntimeChatModel(ModelConfigEntity runtimeModel) {
        return buildRuntimeChatModel(runtimeModel, this.retryTemplate);
    }

    /**
     * 构建运行时 ChatModel，并指定自定义的 Spring AI {@link RetryTemplate}。
     * <p>
     * 用于调用方（如 Wiki 消化管线）已经有自己的外层重试策略，
     * 希望绕过 Spring AI 内层重试、独占重试控制权的场景：传入
     * {@code RetryTemplate.builder().maxAttempts(1).build()} 即可把内层降级为"只跑一次"。
     * <p>
     * DashScope 和 OpenAI-ChatGPT 分支不走 Spring AI 的 RetryTemplate 接口，
     * 本参数对它们无效（它们各自有内部重试或直通）。
     */
    public ChatModel buildRuntimeChatModel(ModelConfigEntity runtimeModel, RetryTemplate retryOverride) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());

        if (protocol == ModelProtocol.DASHSCOPE_NATIVE) {
            DashScopeApi api = buildDashScopeApi(provider);
            DashScopeChatOptions options = buildDashScopeOptions(runtimeModel, provider);
            return dashScopeChatModel.mutate()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build();
        }

        if (protocol == ModelProtocol.OPENAI_CHATGPT) {
            Double temp = runtimeModel.getTemperature() != null ? runtimeModel.getTemperature() : 0.7;
            return new vip.mate.llm.chatgpt.ChatGPTChatModel(
                    chatGPTResponsesClient, runtimeModel.getModelName(), temp);
        }

        if (protocol == ModelProtocol.OPENAI_COMPATIBLE) {
            OpenAiApi api = buildOpenAiApi(provider);
            OpenAiChatOptions options = buildOpenAiOptions(runtimeModel, provider);
            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .retryTemplate(retryOverride)
                    .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                    .build();
        }

        if (protocol == ModelProtocol.ANTHROPIC_MESSAGES) {
            AnthropicApi api = buildAnthropicApi(provider);
            AnthropicChatOptions options = buildAnthropicOptions(runtimeModel);
            return AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .defaultOptions(options)
                    .retryTemplate(retryOverride)
                    .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                    .build();
        }

        throw new MateClawException("err.agent.protocol_limited", "StateGraph 当前仅支持 DashScope 原生协议、OpenAI-compatible 协议和 Anthropic Messages 协议: " + protocol.getId());
    }

    /**
     * build the full multi-provider failover chain for a primary
     * model. Providers are read from {@code mate_model_provider} ordered by
     * {@code fallback_priority ASC} (positive values only), each resolved to
     * its default {@link ModelConfigEntity} and turned into a {@link ChatModel}
     * via {@link #buildRuntimeChatModel(ModelConfigEntity, RetryTemplate)}.
     *
     * <p>Providers whose API key / base URL is missing (build throws) are
     * <b>silently skipped</b> with a warning — fallback should never break
     * the primary call path. The returned list preserves chain order; the
     * streaming helper tries entries in order until one succeeds.</p>
     *
     * <p>The primary model is excluded from the chain when its provider +
     * model name matches a chain entry. Previously only reference equality
     * was checked, which meant a DashScope-primary deployment ended up with
     * {@code null} fallback — the case this design targets.</p>
     *
     * @param primaryModelConfig the {@code ModelConfigEntity} used to build
     *     the primary model; used to identity-filter the chain
     * @return ordered, possibly-empty list of fallback {@link ChatModel}s
     */
    List<vip.mate.llm.failover.FallbackEntry> buildFallbackChain(ModelConfigEntity primaryModelConfig) {
        List<ModelProviderEntity> providers;
        try {
            providers = modelProviderService.listFallbackChain();
        } catch (Exception e) {
            log.warn("[LlmFailover] failed to load fallback chain from DB: {}; running without fallback",
                    e.getMessage());
            return List.of();
        }
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        String primaryProviderId = primaryModelConfig != null ? primaryModelConfig.getProvider() : null;
        String primaryModelName = primaryModelConfig != null ? primaryModelConfig.getModelName() : null;

        List<vip.mate.llm.failover.FallbackEntry> chain = new ArrayList<>();
        for (ModelProviderEntity p : providers) {
            ModelConfigEntity fallbackConfig;
            try {
                fallbackConfig = modelConfigService.getDefaultModelByProvider(p.getProviderId());
            } catch (Exception e) {
                log.warn("[LlmFailover] skipping provider {} — cannot resolve default model: {}",
                        p.getProviderId(), e.getMessage());
                continue;
            }
            if (fallbackConfig == null) {
                log.debug("[LlmFailover] skipping provider {} — no default model configured",
                        p.getProviderId());
                continue;
            }
            if (primaryProviderId != null
                    && primaryProviderId.equals(p.getProviderId())
                    && fallbackConfig.getModelName() != null
                    && fallbackConfig.getModelName().equals(primaryModelName)) {
                log.debug("[LlmFailover] skipping primary {}/{} in fallback chain",
                        primaryProviderId, primaryModelName);
                continue;
            }
            try {
                ChatModel m = buildRuntimeChatModel(fallbackConfig, RetryTemplate.builder().maxAttempts(1).build());
                chain.add(new vip.mate.llm.failover.FallbackEntry(p.getProviderId(), m));
                log.info("[LlmFailover] chain[{}] = {}/{} (priority={})",
                        chain.size(), p.getProviderId(), fallbackConfig.getModelName(),
                        p.getFallbackPriority());
            } catch (Exception e) {
                log.warn("[LlmFailover] skipping provider {} — chat model build failed: {}",
                        p.getProviderId(), e.getMessage());
            }
        }
        return chain;
    }

    /**
     * @deprecated use {@link #buildFallbackChain(ModelConfigEntity)} — the
     *     single-fallback variant cannot represent an ordered chain and only
     *     worked when the primary was a non-DashScope provider.
     */
    @Deprecated
    ChatModel buildFallbackModel(ChatModel primaryModel) {
        try {
            ModelProviderEntity dashScopeProvider = modelProviderService.getProviderConfig("dashscope");
            DashScopeApi api = buildDashScopeApi(dashScopeProvider);
            ModelConfigEntity fallbackModelConfig = modelConfigService.getDefaultModelByProvider("dashscope");
            DashScopeChatOptions options = buildDashScopeOptions(
                    fallbackModelConfig != null ? fallbackModelConfig : modelConfigService.getDefaultModel(), dashScopeProvider);
            ChatModel fallback = dashScopeChatModel.mutate()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build();
            return (fallback != primaryModel) ? fallback : null;
        } catch (Exception e) {
            log.warn("无法构建 DashScope fallback 模型（UI 配置和环境变量均无可用 key），将跳过 fallback: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断 DashScope 内置搜索是否开启：默认开启，仅当显式设为 false 时关闭
     */
    private boolean isDashScopeSearchEnabled(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        // provider generateKwargs 中的 enableSearch 优先级最高（UI 开关直接控制）
        Object kwargsSearch = kwargs.get("enableSearch");
        if (kwargsSearch != null) {
            return Boolean.TRUE.equals(kwargsSearch);
        }
        // model 级别字段：null 视为未设置（DashScope 默认开启），false 视为显式关闭
        if (Boolean.FALSE.equals(runtimeModel.getEnableSearch())) {
            // DB DEFAULT FALSE 导致已有行为 false，此时如果是 DashScope 仍默认开启
            // 只有用户手动设置过才会有明确含义，但目前无法区分，所以 DashScope 默认开启
            return true;
        }
        return true; // DashScope 默认开启
    }

    // ==================== Prompt 构建 ====================

    private String buildEnhancedPrompt(AgentEntity entity, boolean builtinSearchEnabled) {
        // 通过 MemoryManager 从所有 MemoryProvider 组装系统提示词（快照冻结）
        String memoryPrompt = memoryManager.buildSystemPromptBlock(entity.getId());
        String basePrompt = (memoryPrompt != null && !memoryPrompt.isBlank())
                ? memoryPrompt
                : (entity.getSystemPrompt() != null ? entity.getSystemPrompt() : "");

        // 使用 skill runtime 构建技能增强（per-agent 绑定过滤）
        Set<Long> boundSkillIds = agentBindingService.getBoundSkillIds(entity.getId());
        String skillEnhancement = skillRuntimeService.buildSkillPromptEnhancement(boundSkillIds);

        // 工具调用指导
        String toolGuidance = """

                ## Runtime Context
                - Current Agent ID: %s

                ## Workspace Memory Guidelines
                Your durable memory is stored in database-backed workspace markdown files for this agent:
                - `PROFILE.md`: stable user profile, preferences, collaboration style
                - `MEMORY.md`: distilled long-term memory, durable facts, lessons, recurring patterns
                - `memory/YYYY-MM-DD.md`: daily notes, raw events, temporary observations, open loops

                Use workspace memory tools instead of local filesystem tools for those files:
                - `list_workspace_memory_files(agentId=..., filenamePrefix=...)`
                - `read_workspace_memory_file(agentId=..., filename=...)`
                - `write_workspace_memory_file(agentId=..., filename=..., content=...)`
                - `edit_workspace_memory_file(agentId=..., filename=..., oldText=..., newText=...)`

                Memory writing policy:
                - Stable user preference, identity, collaboration habit -> `PROFILE.md`
                - Stable project fact, workflow, tool setup, lesson learned, recurring decision -> `MEMORY.md`
                - One-off event, meeting note, temporary context, today's decision trace -> `memory/YYYY-MM-DD.md`
                - Read before write unless you are creating a brand new daily note
                - Do not store secrets or highly sensitive data unless the user explicitly asks
                - Updating workspace memory files is internal state maintenance for this agent and can be done proactively when useful

                Memory emergence policy:
                - If the same preference, constraint, workflow, or lesson appears repeatedly, consolidate it from daily notes into `MEMORY.md`
                - Prefer updating an existing section over appending duplicate bullets
                - Treat `MEMORY.md` as a compact mental model, not a raw transcript dump
                - When answering tasks involving prior decisions, preferences, habits, or ongoing work, proactively consult relevant workspace memory first

                ## Structured Memory Tools
                For discrete, typed facts use structured memory tools (separate from workspace files):
                - `remember_structured(agentId, type, key, content)` — store a typed entry
                - `recall_structured(agentId, type, keyword)` — search entries by type and/or keyword
                - `forget_structured(agentId, type, key)` — remove an entry

                Types:
                - `user`: preferences, expertise, communication style, role
                - `feedback`: behavioral corrections or confirmed approaches (include WHY)
                - `project`: decisions, deadlines, constraints not derivable from code/git
                - `reference`: pointers to external systems (Linear boards, Grafana dashboards, Slack channels)

                Use workspace memory tools (MEMORY.md, daily notes) for long-form narrative notes.
                Use structured memory tools for key-value facts the system can query efficiently.

                ## Session Search
                - `session_search(agentId, currentConversationId, mode, query, limit)` — search conversation history
                - mode="recent": list recent conversations (titles, times, message counts)
                - mode="search": keyword full-text search across past messages
                - Use this to recall previous discussions, look up past decisions, or find context from earlier conversations

                ## Tool Usage Guidelines
                When you have available tools, use them to access local system information, files, or execute commands.
                Do not assume you cannot access local resources - try calling the appropriate tool first.
                If a tool requires approval due to security policies, the system will prompt the user for confirmation.
                Only state you cannot access something if no relevant tool is available.

                ## Multi-Part Question Guidelines
                When the user asks multiple questions or requests multiple tasks in a single message:
                1. Structure your final answer with numbered sections, one per sub-task
                2. Each section must contain the complete, detailed result for that sub-task
                3. Never compress earlier sub-tasks into summary sentences while expanding the last one
                4. If observations were summarized during processing, reconstruct each section from the summary
                5. Treat each sub-task's result as equally important regardless of processing order

                ## File Reading Guidelines

                **Text Files** (use read_file):
                For .txt, .md, .json, .yaml, .csv, .log, .py, .java, .js, .html, .xml, .sql, .conf, .ini, .toml files.

                **Office/PDF Documents** (DO NOT use read_file):
                For .pdf, .docx, .doc, .xlsx, .xls, .pptx, .ppt files, NEVER use read_file.
                Instead use:
                - detect_file_type(filePath="...") - to check file type first
                - extract_document_text(filePath="...") - general document extraction
                - extract_pdf_text(filePath="...") - for PDF files
                - extract_docx_text(filePath="...") - for Word documents

                Example workflow for document:
                1. detect_file_type(filePath="/path/to/document.pdf")
                2. Based on result, use extract_pdf_text() or extract_document_text()
                3. Process the extracted text content

                If you try to read a PDF/Office file with read_file, you will get binary garbage or an error.
                """.formatted(entity.getId());

        String searchGuidance = "";
        if (builtinSearchEnabled) {
            searchGuidance = """

                ## Web Search Capability

                You have **dual search capability**:
                1. **Built-in search** (preferred): Your responses automatically incorporate live web search results from the model provider. For most queries, answer directly — your response already includes real-time search data.
                2. **search tool** (supplementary): Available as a fallback. Supports advanced parameters: `freshness` (day/week/month/year), `language` (zh-CN/en), `count` (1-10).

                ### Priority Rules
                - **Default**: Answer directly using built-in search. Do NOT say you cannot search — your replies already include live results.
                - **Use search tool** ONLY when: you need precise time filtering (e.g., user asks for "yesterday's news" → call search with freshness=day), specific language results, or your built-in results feel insufficient.
                - **NEVER** call both browser_use and search tool for the same query.
                - When searching for news, use the standard format: `📰 [Category] Title — Source | Time + Summary`, up to 5 results per category.
                """;
        }

        // Wiki 知识库上下文注入
        String wikiContext = wikiContextService.buildWikiContext(entity.getId());

        return basePrompt + skillEnhancement + toolGuidance + searchGuidance + wikiContext;
    }

    // ==================== 模型选项构建 ====================

    private DashScopeChatOptions buildDashScopeOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        DashScopeChatOptions.DashScopeChatOptionsBuilder builder = DashScopeChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);

        if (StringUtils.hasText(runtimeModel.getModelName())) {
            builder.withModel(runtimeModel.getModelName());
        }
        if (runtimeModel.getTemperature() != null) {
            builder.withTemperature(runtimeModel.getTemperature());
        }
        if (runtimeModel.getMaxTokens() != null) {
            builder.withMaxToken(runtimeModel.getMaxTokens());
        }
        if (runtimeModel.getTopP() != null) {
            builder.withTopP(runtimeModel.getTopP());
        }
        // 内置搜索：复用统一判断方法
        if (isDashScopeSearchEnabled(runtimeModel, provider)) {
            builder.withEnableSearch(true);
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            if (StringUtils.hasText(strategy)) {
                builder.withSearchOptions(DashScopeApiSpec.SearchOptions.builder()
                        .searchStrategy(strategy)
                        .enableSource(true)
                        .enableCitation(true)
                        .build());
            }
        }
        return builder.build();
    }

    private OpenAiChatOptions buildOpenAiOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String modelName = runtimeModel.getModelName();
        ModelFamily family = ModelFamily.detect(modelName);

        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }

        // temperature：部分模型族强制 1.0
        Double temperature = resolveOpenAiTemperature(modelName, runtimeModel.getTemperature(), kwargs, family);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        // max_tokens / max_completion_tokens：按模型族路由
        if (family.suppressMaxTokens()) {
            // OPENAI_REASONING 族：禁止 max_tokens，改用 max_completion_tokens
            // fallback 优先级：kwargs.maxCompletionTokens > kwargs.maxTokens > config.maxTokens
            Integer kwargsMaxTokens = resolveIntegerOption("maxTokens", runtimeModel.getMaxTokens(), kwargs);
            Integer maxCompletionTokens = resolveIntegerOption("maxCompletionTokens", kwargsMaxTokens, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
            log.debug("ModelFamily {} suppressed max_tokens, using max_completion_tokens={} for model {}",
                    family, maxCompletionTokens, modelName);
        } else {
            // 其他模型族：正常使用 max_tokens
            Integer maxTokens = resolveIntegerOption("maxTokens", runtimeModel.getMaxTokens(), kwargs);
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            // 仍允许通过 generateKwargs 手动指定 maxCompletionTokens
            Integer maxCompletionTokens = resolveIntegerOption("maxCompletionTokens", null, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
        }

        // top_p：部分模型族禁止发送
        Double topP = resolveOpenAiTopP(modelName, runtimeModel.getTopP(), kwargs, family);
        if (topP != null) {
            builder.topP(topP);
        }

        // reasoning_effort：仅支持的模型族才注入
        String reasoningEffort = resolveReasoningEffort(modelName, kwargs, family);
        if (StringUtils.hasText(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
        }

        // 内置搜索：模型级字段优先，provider generateKwargs 作为 fallback
        boolean searchEnabled = Boolean.TRUE.equals(runtimeModel.getEnableSearch())
                || Boolean.TRUE.equals(kwargs.get("enableSearch"));
        if (searchEnabled) {
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize contextSize;
            try {
                contextSize = StringUtils.hasText(strategy)
                        ? OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.valueOf(strategy.toUpperCase())
                        : OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            } catch (IllegalArgumentException e) {
                contextSize = OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            }
            builder.webSearchOptions(new OpenAiApi.ChatCompletionRequest.WebSearchOptions(contextSize, null));
        }

        OpenAiChatOptions options = builder.build();
        options.setInternalToolExecutionEnabled(false);
        // 注意：不设置 parallelToolCalls — 设为 false 会导致无 tools 时 OpenAI 返回 400：
        // "parallel_tool_calls is only allowed when 'tools' are specified"
        // 保持 null 让 Spring AI 不序列化该字段，由各 Node 在有 tools 时自行控制。
        options.setStreamUsage(true);
        return options;
    }

    // ==================== OpenAI API 构建 ====================

    OpenAiApi buildOpenAiApi(ModelProviderEntity provider) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.provider_not_configured", "Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.provider_apikey_invalid", "Provider API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.agent.provider_baseurl_missing", "Provider Base URL 未配置: " + provider.getProviderId());
        }
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        MultiValueMap<String, String> headers = buildOpenAiHeaders(kwargs);
        String completionsPath = resolveOpenAiCompletionsPath(baseUrl, kwargs);
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder));
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);

        // Spring AI OpenAiApi 构造函数会先 set User-Agent 为 "spring-ai"，再 addAll 我们的 headers，
        // 导致自定义 User-Agent 被追加而非覆盖。因此对需要伪装客户端身份的 provider（如 kimi-code），
        // 通过 RestClient/WebClient 拦截器在请求发出前强制覆盖 headers。
        Map<String, String> overrideHeaders = extractOverrideHeaders(kwargs);
        if (!overrideHeaders.isEmpty()) {
            restClientBuilder = restClientBuilder.requestInterceptor((request, body, execution) -> {
                HttpHeaders reqHeaders = request.getHeaders();
                overrideHeaders.forEach(reqHeaders::set);
                return execution.execute(request, body);
            });
            webClientBuilder = webClientBuilder.filter((request, next) -> {
                org.springframework.web.reactive.function.client.ClientRequest modified =
                        org.springframework.web.reactive.function.client.ClientRequest.from(request)
                                .headers(h -> overrideHeaders.forEach(h::set))
                                .build();
                return next.exchange(modified);
            });
        }

        boolean kimiSearchEnabled = isKimiProvider(provider)
                && Boolean.TRUE.equals(kwargs.get("enableSearch"));

        return new OpenAiApi(
                baseUrl,
                new SimpleApiKey(apiKey.trim()),
                headers,
                completionsPath,
                "/v1/embeddings",
                restClientBuilder,
                webClientBuilder,
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) {
            @Override
            public org.springframework.http.ResponseEntity<OpenAiApi.ChatCompletion> chatCompletionEntity(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = patchReasoningContent(chatRequest);
                chatRequest = stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                try {
                    return super.chatCompletionEntity(chatRequest, additionalHttpHeader);
                } catch (WebClientResponseException e) {
                    logOpenAiError(provider, e);
                    throw e;
                }
            }

            @Override
            public Flux<OpenAiApi.ChatCompletionChunk> chatCompletionStream(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = patchReasoningContent(chatRequest);
                chatRequest = stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                return super.chatCompletionStream(chatRequest, additionalHttpHeader)
                        .doOnError(error -> {
                            if (error instanceof WebClientResponseException e) {
                                logOpenAiError(provider, e);
                            }
                        });
            }
        };
    }

    // ==================== DashScope API 构建 ====================

    private DashScopeApi buildDashScopeApi(ModelProviderEntity provider) {
        DashScopeApi.Builder builder = DashScopeApi.builder();

        // API Key 回落链：provider UI 配置 → 环境变量/application.yml → 默认 bean 反射
        String apiKey = provider != null ? provider.getApiKey() : null;
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = dashScopeConnectionProperties.getApiKey();
        }
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = readApiKeyFromDefaultChatModel();
        }
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.dashscope_key_missing", "DashScope API Key 未配置，请在模型设置中填写 dashscope 的 API Key，或设置 DASHSCOPE_API_KEY 环境变量");
        }
        builder.apiKey(apiKey.trim());

        // Base URL 回落链：provider UI 配置 → 环境变量/application.yml → 默认 bean 反射
        String baseUrl = provider != null ? provider.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = dashScopeConnectionProperties.getBaseUrl();
        }
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = readBaseUrlFromDefaultChatModel();
        }
        String normalizedBaseUrl = normalizeDashScopeBaseUrl(baseUrl);
        if (StringUtils.hasText(normalizedBaseUrl)) {
            builder.baseUrl(normalizedBaseUrl);
        }
        return builder.build();
    }

    // ==================== Anthropic API 构建 ====================

    private AnthropicApi buildAnthropicApi(ModelProviderEntity provider) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.anthropic_not_configured", "Anthropic Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.anthropic_key_invalid", "Anthropic API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = provider.getBaseUrl();
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder));
        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder);

        AnthropicApi.Builder builder = AnthropicApi.builder()
                .apiKey(apiKey.trim())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    private AnthropicChatOptions buildAnthropicOptions(ModelConfigEntity runtimeModel) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        if (StringUtils.hasText(runtimeModel.getModelName())) {
            builder.model(runtimeModel.getModelName());
        }

        // Extended thinking: 通过 ThinkingLevelHolder 获取请求级思考深度
        String thinkingLevel = ThinkingLevelHolder.get();
        boolean thinkingEnabled = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);

        if (thinkingEnabled) {
            // Anthropic thinking 模式下：temperature 必须为 1，不能设 top_p
            // budget_tokens 根据级别映射
            int budgetTokens = switch (thinkingLevel.toLowerCase()) {
                case "low" -> 4096;
                case "medium" -> 8192;
                case "high" -> 16384;
                case "max" -> 32768;
                default -> 16384;
            };
            builder.thinking(org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType.ENABLED, budgetTokens);
            // Thinking 模式要求 max_tokens 足够大（含 thinking tokens）
            builder.maxTokens(Math.max(budgetTokens + 4096,
                    runtimeModel.getMaxTokens() != null ? runtimeModel.getMaxTokens() : 8192));
            // Anthropic thinking 模式要求 temperature=1
            builder.temperature(1.0);
        } else {
            // 非 thinking 模式：正常设置参数
            // Anthropic API does not allow temperature and top_p to be specified simultaneously.
            if (runtimeModel.getTemperature() != null) {
                builder.temperature(runtimeModel.getTemperature());
            } else if (runtimeModel.getTopP() != null) {
                builder.topP(runtimeModel.getTopP());
            }
            // RFC-025 Change 5: 非正值 maxTokens 会被 Anthropic API 直接拒绝；本地提前拦截
            // 并 fallback 到 4096，避免错误信息在运行时才暴露、也防止坏配置透传
            Integer configuredMax = runtimeModel.getMaxTokens();
            if (configuredMax != null && configuredMax > 0) {
                builder.maxTokens(configuredMax);
            } else {
                if (configuredMax != null) {
                    log.warn("Ignoring non-positive Anthropic maxTokens={} for model {}; falling back to 4096",
                            configuredMax, runtimeModel.getModelName());
                }
                builder.maxTokens(4096);
            }
        }
        // RFC-014: 接入 Anthropic prompt caching（spring-ai 1.1.4 一等支持）
        // 通过 cacheOptions 配置 system / tools / conversation history 自动打 cache_control，
        // 多轮对话场景可节省 50–75% 输入 token 成本。
        builder.cacheOptions(anthropicCacheOptionsFactory.build());

        return builder.internalToolExecutionEnabled(false).build();
    }

    // ==================== 参数解析辅助方法 ====================

    private Double resolveOpenAiTemperature(String modelName, Double configuredTemperature,
                                               Map<String, Object> kwargs, ModelFamily family) {
        Double overriddenTemperature = resolveDoubleOption("temperature", configuredTemperature, kwargs);
        if (family.fixedTemperatureOne()) {
            if (overriddenTemperature == null || Double.compare(overriddenTemperature, 1.0d) != 0) {
                log.info("ModelFamily {} forced temperature=1.0 for model {}", family, modelName);
            }
            return 1.0d;
        }
        return overriddenTemperature;
    }

    private Double resolveOpenAiTopP(String modelName, Double configuredTopP,
                                     Map<String, Object> kwargs, ModelFamily family) {
        if (family.suppressTopP()) {
            return null;
        }
        return resolveDoubleOption("topP", configuredTopP, kwargs);
    }

    private boolean requiresFixedTemperatureOne(String modelName) {
        return ModelFamily.detect(modelName).fixedTemperatureOne();
    }

    private String resolveReasoningEffort(String modelName, Map<String, Object> kwargs, ModelFamily family) {
        // generateKwargs 显式覆盖始终优先
        Object value = findOptionValue(kwargs, "reasoningEffort");
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        // 仅支持 reasoning_effort 的模型族才自动注入默认值
        if (family.isThinking() && family.supportsReasoningEffort()) {
            return "medium";
        }
        return null;
    }

    private boolean isThinkingModel(String modelName) {
        return ModelFamily.detect(modelName).isThinking();
    }

    /**
     * 从 ModelConfigEntity 中解析 reasoningEffort，用于传递给 StepExecutionNode / ReasoningNode。
     * 复用已有的 resolveReasoningEffort + isThinkingModel 逻辑。
     */
    private String resolveReasoningEffortForModel(ModelConfigEntity runtimeModel) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(runtimeModel.getProvider());
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        ModelFamily family = ModelFamily.detect(runtimeModel.getModelName());
        return resolveReasoningEffort(runtimeModel.getModelName(), kwargs, family);
    }

    private Double resolveDoubleOption(String key, Double fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid double generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }

    private Integer resolveIntegerOption(String key, Integer fallback, Map<String, Object> kwargs) {
        Object value = findOptionValue(kwargs, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                log.warn("Invalid integer generateKwargs value for {}: {}", key, text);
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Object findOptionValue(Map<String, Object> kwargs, String key) {
        Object direct = findKwarg(kwargs, key);
        if (direct != null) {
            return direct;
        }
        String snakeCase = key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        if (!snakeCase.equals(key)) {
            return findKwarg(kwargs, snakeCase);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object findKwarg(Map<String, Object> kwargs, String key) {
        if (kwargs == null || kwargs.isEmpty()) {
            return null;
        }
        if (kwargs.containsKey(key)) {
            return kwargs.get(key);
        }
        Object chatOptions = kwargs.get("chatOptions");
        if (chatOptions instanceof Map<?, ?> optionsMap) {
            return ((Map<String, Object>) optionsMap).get(key);
        }
        return null;
    }

    // ==================== URL 规范化 ====================

    private String normalizeDashScopeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String normalized = baseUrl.trim();
        // 去掉 OpenAI 兼容模式路径（用户可能从兼容模式 URL 迁移过来）
        int compatibleIndex = normalized.indexOf("/compatible-mode/");
        if (compatibleIndex >= 0) {
            normalized = normalized.substring(0, compatibleIndex);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // 如果结果是 DashScope 默认地址，返回 null 让 SDK 使用内置默认值，避免路径拼接问题
        if ("https://dashscope.aliyuncs.com".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private String normalizeOpenAiBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    // ==================== Kimi 内置搜索 ====================

    private static boolean isKimiProvider(ModelProviderEntity provider) {
        if (provider == null) return false;
        String id = provider.getProviderId();
        return "kimi-cn".equals(id) || "kimi-intl".equals(id);
    }

    /**
     * 为 Kimi 请求注入 $web_search builtin tool。
     * Kimi 的内置搜索通过 tools 数组中声明 {"type":"builtin_function","function":{"name":"$web_search"}} 实现。
     * 由于 Spring AI 的 FunctionTool.Type 只有 FUNCTION，无法直接构造 builtin_function 类型，
     * 因此通过 extraBody 注入原始 JSON 结构覆盖 tools 字段（包含原有 tools + $web_search）。
     */
    private static OpenAiApi.ChatCompletionRequest injectKimiWebSearch(OpenAiApi.ChatCompletionRequest request) {
        // 构造 $web_search entry 作为 Map
        Map<String, Object> webSearchTool = Map.of(
                "type", "builtin_function",
                "function", Map.of("name", "$web_search")
        );

        // 将原有 tools 转为 List<Map> 并追加 $web_search
        List<Map<String, Object>> allTools = new ArrayList<>();
        if (request.tools() != null) {
            for (OpenAiApi.FunctionTool tool : request.tools()) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("type", "function");
                if (tool.getFunction() != null) {
                    Map<String, Object> funcMap = new LinkedHashMap<>();
                    funcMap.put("name", tool.getFunction().getName());
                    if (tool.getFunction().getDescription() != null) {
                        funcMap.put("description", tool.getFunction().getDescription());
                    }
                    if (tool.getFunction().getParameters() != null) {
                        funcMap.put("parameters", tool.getFunction().getParameters());
                    }
                    if (tool.getFunction().getStrict() != null) {
                        funcMap.put("strict", tool.getFunction().getStrict());
                    }
                    toolMap.put("function", funcMap);
                }
                allTools.add(toolMap);
            }
        }
        allTools.add(webSearchTool);

        // 通过 extraBody 注入 tools（覆盖原有 tools 字段），同时清空原 tools 避免重复序列化
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (request.extraBody() != null) {
            extraBody.putAll(request.extraBody());
        }
        extraBody.put("tools", allTools);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                null,  // tools — 清空，由 extraBody 接管
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                extraBody
        );
    }

    // ==================== 反射读取默认模型配置 ====================

    private String readApiKeyFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) {
                return null;
            }
            Field apiKeyField = DashScopeApi.class.getDeclaredField("apiKey");
            apiKeyField.setAccessible(true);
            Object apiKey = apiKeyField.get(api);
            if (apiKey instanceof org.springframework.ai.model.ApiKey key) {
                return key.getValue();
            }
        } catch (Exception e) {
            log.warn("Failed to read API key from default DashScopeChatModel: {}", e.getMessage());
        }
        return null;
    }

    private String readBaseUrlFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) {
                return null;
            }
            Field baseUrlField = DashScopeApi.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            Object baseUrl = baseUrlField.get(api);
            return baseUrl instanceof String value ? value : null;
        } catch (Exception e) {
            log.warn("Failed to read baseUrl from default DashScopeChatModel: {}", e.getMessage());
            return null;
        }
    }

    private DashScopeApi readDashScopeApiFromDefaultChatModel() throws NoSuchFieldException, IllegalAccessException {
        Field apiField = DashScopeChatModel.class.getDeclaredField("dashscopeApi");
        apiField.setAccessible(true);
        Object api = apiField.get(dashScopeChatModel);
        return api instanceof DashScopeApi dashScopeApi ? dashScopeApi : null;
    }

    // ==================== 日志辅助 ====================

    private MultiValueMap<String, String> buildOpenAiHeaders(Map<String, Object> kwargs) {
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("User-Agent", "MateClaw/1.0");
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.set(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return headers;
    }

    /**
     * RFC-012 M1：给 LLM 调用走的 RestClient 显式配置超时，避免 socket 永久挂起等待。
     * <p>
     * 使用 {@link JdkClientHttpRequestFactory}（基于 Java 11+ {@link HttpClient}），原因：
     * <ul>
     *   <li>原生支持 HTTP/2 / ALPN 协商（Kimi 等现代 LLM provider 默认 HTTP/2）</li>
     *   <li>自动处理 {@code Content-Encoding: gzip} 解压（{@code SimpleClientHttpRequestFactory}
     *       基于旧的 {@code HttpURLConnection}，不会自动解压，会把 gzip 流误标为
     *       {@code application/octet-stream} 导致 RestClient 抛 "Error extracting response"）</li>
     *   <li>对 chunked transfer + 非标准 content-type 的回退处理符合现代 spec</li>
     * </ul>
     * <p>
     * connectTimeout=10s（任何 LLM 提供方都不该超过这个建立连接时间）；
     * readTimeout=180s（覆盖 nginx 60s 网关超时 + 留足真实长响应余量；超时后由上层 retry 接管）。
     */
    private RestClient.Builder applyHttpTimeouts(RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(Duration.ofSeconds(180));
        return builder.requestFactory(rf);
    }

    /**
     * 从 generateKwargs.headers 中提取需要强制覆盖的 headers。
     * 用于通过 RestClient/WebClient 拦截器绕过 Spring AI OpenAiApi 的默认 User-Agent。
     */
    private Map<String, String> extractOverrideHeaders(Map<String, Object> kwargs) {
        Map<String, String> result = new java.util.HashMap<>();
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return result;
    }

    private String resolveOpenAiCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        Object raw = kwargs.get("completionsPath");
        String path = raw instanceof String value && StringUtils.hasText(value) ? value.trim() : "/v1/chat/completions";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (baseUrl.endsWith("/v1") && path.startsWith("/v1/")) {
            path = path.substring(3);
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
        }
        return path;
    }

    /**
     * 修补 assistant 消息缺失的 reasoningContent 字段。
     * <p>
     * Spring AI 1.1.3 在将 AssistantMessage 转回 ChatCompletionMessage 时不会设置 reasoningContent，
     * 导致某些启用 thinking 模式的 API（如 Kimi K2.5）在多轮对话中报错：
     * "thinking is enabled but reasoning_content is missing in assistant tool call message"
     * <p>
     * 触发条件（放宽）：
     * <ul>
     *   <li>条件 A：请求明确设置了 reasoningEffort</li>
     *   <li>条件 B：消息历史中已有 assistant 消息携带 reasoningContent（说明模型天然启用了 thinking）</li>
     * </ul>
     * 修复策略：为缺失 reasoningContent 的 assistant tool_call 消息注入空字符串 "" 以满足 API 校验。
     * 使用 record canonical constructor 重建 ChatCompletionRequest，避免反射修改不可变字段。
     */
    private static OpenAiApi.ChatCompletionRequest patchReasoningContent(OpenAiApi.ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        // 判断是否处于 thinking 模式
        boolean thinkingMode = request.reasoningEffort() != null;
        if (!thinkingMode) {
            thinkingMode = requiresReasoningContentPatch(request.model());
        }
        if (!thinkingMode) {
            thinkingMode = request.messages().stream().anyMatch(msg ->
                    msg.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
                            && msg.reasoningContent() != null);
        }
        if (!thinkingMode) {
            return request;
        }

        // 检查是否有需要补丁的消息
        boolean needsPatch = request.messages().stream().anyMatch(msg ->
                msg.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
                        && msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                        && msg.reasoningContent() == null);
        if (!needsPatch) {
            return request;
        }

        // 重建消息列表，为缺失 reasoningContent 的 assistant tool call 消息注入 ""
        List<OpenAiApi.ChatCompletionMessage> patched = request.messages().stream().map(msg -> {
            if (msg.role() == OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
                    && msg.toolCalls() != null && !msg.toolCalls().isEmpty()
                    && msg.reasoningContent() == null) {
                return new OpenAiApi.ChatCompletionMessage(
                        msg.rawContent(), msg.role(), msg.name(), msg.toolCallId(),
                        msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                        msg.annotations(), " ");
            }
            return msg;
        }).toList();

        // 用 record canonical constructor 重建 request（不用反射）
        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                request.reasoningEffort(),
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    /**
     * GPT-5 兼容性：在 /v1/chat/completions 路径下，tools 与 reasoning_effort 不可同时存在。
     * <p>
     * 当检测到 gpt-5* 模型同时携带 tools 和 reasoning_effort 时，自动移除 reasoning_effort 并记录警告日志。
     * 若需使用 reasoning_effort，应改用 /v1/responses 接口（通过 generateKwargs 的 completionsPath 配置）。
     */
    private static OpenAiApi.ChatCompletionRequest stripReasoningEffortIfIncompatible(
            OpenAiApi.ChatCompletionRequest request) {
        if (request.reasoningEffort() == null) {
            return request;
        }
        if (request.tools() == null || request.tools().isEmpty()) {
            return request;
        }
        String model = request.model();
        if (model == null || !model.trim().toLowerCase().startsWith("gpt-5")) {
            return request;
        }

        log.warn("[GPT-5 兼容] 模型 {} 在 chat/completions 下同时携带 tools 和 reasoning_effort，"
                        + "自动移除 reasoning_effort 以避免 400 错误。"
                        + "如需 reasoning_effort，请将 completionsPath 配置为 /v1/responses",
                model);

        return new OpenAiApi.ChatCompletionRequest(
                request.messages(),
                request.model(),
                request.store(),
                request.metadata(),
                request.frequencyPenalty(),
                request.logitBias(),
                request.logprobs(),
                request.topLogprobs(),
                request.maxTokens(),
                request.maxCompletionTokens(),
                request.n(),
                request.outputModalities(),
                request.audioParameters(),
                request.presencePenalty(),
                request.responseFormat(),
                request.seed(),
                request.serviceTier(),
                request.stop(),
                request.stream(),
                request.streamOptions(),
                request.temperature(),
                request.topP(),
                request.tools(),
                request.toolChoice(),
                request.parallelToolCalls(),
                request.user(),
                null,  // reasoningEffort — 移除
                request.webSearchOptions(),
                request.verbosity(),
                request.promptCacheKey(),
                request.safetyIdentifier(),
                request.extraBody()
        );
    }

    private static boolean requiresReasoningContentPatch(String modelName) {
        ModelFamily family = ModelFamily.detect(modelName);
        return family.isThinking();
    }

    /**
     * 将 Spring AI 错误地序列化为 image_url 的视频内容块转换为 video_url 格式。
     * <p>
     * Spring AI 1.x 的 MediaContent 没有 video_url 类型，所有非 audio/pdf 的 Media
     * 都被序列化为 image_url。智谱 GLM-5V 等模型要求视频使用 video_url 格式，
     * 否则会报"图片输入格式/解析错误"。
     * <p>
     * 此方法遍历 user 消息的 rawContent，将 data:video/* 前缀的 image_url 替换为 video_url。
     */
    @SuppressWarnings("unchecked")
    private static OpenAiApi.ChatCompletionRequest patchVideoMediaContent(OpenAiApi.ChatCompletionRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        boolean needsPatch = false;
        for (var msg : request.messages()) {
            if (msg.role() == OpenAiApi.ChatCompletionMessage.Role.USER) {
                Object raw = msg.rawContent();
                if (raw instanceof List<?> parts) {
                    for (Object part : parts) {
                        // 检查是否为 MediaContent record
                        if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                                && "image_url".equals(mc.type())
                                && mc.imageUrl() != null
                                && mc.imageUrl().url() != null
                                && mc.imageUrl().url().startsWith("data:video/")) {
                            needsPatch = true;
                            break;
                        }
                        // 检查是否为 Map（Spring AI 内部用 LinkedHashMap 表示 content parts）
                        if (part instanceof java.util.Map<?,?> map) {
                            Object type = map.get("type");
                            if ("image_url".equals(type)) {
                                Object imgUrlObj = map.get("image_url");
                                if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                                    Object url = imgUrl.get("url");
                                    if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                                        needsPatch = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (needsPatch) break;
        }
        if (!needsPatch) {
            return request;
        }

        List<OpenAiApi.ChatCompletionMessage> patched = request.messages().stream().map(msg -> {
            if (msg.role() != OpenAiApi.ChatCompletionMessage.Role.USER || !(msg.rawContent() instanceof List<?> parts)) {
                return msg;
            }
            List<Object> newParts = new ArrayList<>();
            for (Object part : parts) {
                String videoDataUrl = null;

                // 场景 1：MediaContent record（Spring AI 原生构建）
                if (part instanceof OpenAiApi.ChatCompletionMessage.MediaContent mc
                        && "image_url".equals(mc.type())
                        && mc.imageUrl() != null && mc.imageUrl().url() != null
                        && mc.imageUrl().url().startsWith("data:video/")) {
                    videoDataUrl = mc.imageUrl().url();
                }
                // 场景 2：Map（Jackson 反序列化或 Spring AI 内部用 Map 表示）
                if (videoDataUrl == null && part instanceof java.util.Map<?,?> map
                        && "image_url".equals(map.get("type"))) {
                    Object imgUrlObj = map.get("image_url");
                    if (imgUrlObj instanceof java.util.Map<?,?> imgUrl) {
                        Object url = imgUrl.get("url");
                        if (url instanceof String urlStr && urlStr.startsWith("data:video/")) {
                            videoDataUrl = urlStr;
                        }
                    }
                }

                if (videoDataUrl != null) {
                    // 替换为 video_url 格式
                    newParts.add(Map.of(
                            "type", "video_url",
                            "video_url", Map.of("url", videoDataUrl)
                    ));
                } else {
                    newParts.add(part);
                }
            }
            return new OpenAiApi.ChatCompletionMessage(
                    newParts, msg.role(), msg.name(), msg.toolCallId(),
                    msg.toolCalls(), msg.refusal(), msg.audioOutput(),
                    msg.annotations(), msg.reasoningContent());
        }).toList();

        return new OpenAiApi.ChatCompletionRequest(
                patched,
                request.model(), request.store(), request.metadata(),
                request.frequencyPenalty(), request.logitBias(),
                request.logprobs(), request.topLogprobs(),
                request.maxTokens(), request.maxCompletionTokens(),
                request.n(), request.outputModalities(), request.audioParameters(),
                request.presencePenalty(), request.responseFormat(),
                request.seed(), request.serviceTier(), request.stop(),
                request.stream(), request.streamOptions(),
                request.temperature(), request.topP(),
                request.tools(), request.toolChoice(), request.parallelToolCalls(),
                request.user(), request.reasoningEffort(),
                request.webSearchOptions(), request.verbosity(),
                request.promptCacheKey(), request.safetyIdentifier(),
                request.extraBody()
        );
    }

    private void logOpenAiRequest(ModelProviderEntity provider, OpenAiApi.ChatCompletionRequest chatRequest) {
        try {
            log.info("OpenAI-compatible request: provider={}, body={}",
                    provider.getProviderId(), objectMapper.writeValueAsString(chatRequest));
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAI-compatible request for {}: {}",
                    provider.getProviderId(), e.getMessage());
        }
    }

    private void logOpenAiError(ModelProviderEntity provider, WebClientResponseException e) {
        log.error("OpenAI-compatible error: provider={}, status={}, body={}",
                provider.getProviderId(), e.getStatusCode(), e.getResponseBodyAsString());
    }
}
