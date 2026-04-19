package vip.mate.agent.chatmodel;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.chatmodel.ChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * Thin strategy adapter for {@link ModelProtocol#DASHSCOPE_NATIVE}.
 *
 * <p>This is a deliberate <b>delegating</b> implementation: it calls back into
 * {@link AgentGraphBuilder}'s package-private helpers ({@code buildDashScopeApi},
 * {@code buildDashScopeOptions}) rather than owning the build logic itself.
 * The goal of this PR is to install the strategy seam (so
 * {@code ProviderChatModelFactory} can route requests without circular
 * dependencies) without taking on the risk of relocating ~600 lines of
 * provider-specific helpers in a single change.</p>
 *
 * <p>A follow-up PR (PR-0b in the plan) moves the helpers into this class so
 * {@code AgentGraphBuilder} can shed the protocol-specific code entirely.</p>
 *
 * <p>{@code @Lazy} on the {@link AgentGraphBuilder} dependency breaks the
 * factory ↔ builder ↔ AgentGraphBuilder bean-creation cycle:
 * AgentGraphBuilder constructs ProviderChatModelFactory, the factory needs
 * builders, and this builder needs AgentGraphBuilder back. The lazy proxy
 * defers AgentGraphBuilder resolution until the first {@link #build} call.</p>
 */
@Component
public class AgentDashScopeChatModelBuilder implements ChatModelBuilder {

    private final AgentGraphBuilder agentGraphBuilder;
    private final DashScopeChatModel dashScopeChatModel;

    public AgentDashScopeChatModelBuilder(@Lazy AgentGraphBuilder agentGraphBuilder,
                                          DashScopeChatModel dashScopeChatModel) {
        this.agentGraphBuilder = agentGraphBuilder;
        this.dashScopeChatModel = dashScopeChatModel;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.DASHSCOPE_NATIVE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        DashScopeApi api = agentGraphBuilder.buildDashScopeApi(provider);
        DashScopeChatOptions options = agentGraphBuilder.buildDashScopeOptions(model, provider);
        return dashScopeChatModel.mutate()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }
}
