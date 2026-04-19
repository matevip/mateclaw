package vip.mate.agent.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.chatmodel.ChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * Thin strategy adapter for {@link ModelProtocol#ANTHROPIC_MESSAGES}.
 * See {@link AgentDashScopeChatModelBuilder} for the delegate-pattern rationale.
 */
@Component
public class AgentAnthropicChatModelBuilder implements ChatModelBuilder {

    private final AgentGraphBuilder agentGraphBuilder;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    public AgentAnthropicChatModelBuilder(
            @Lazy AgentGraphBuilder agentGraphBuilder,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.agentGraphBuilder = agentGraphBuilder;
        this.observationRegistryProvider = observationRegistryProvider;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        AnthropicApi api = agentGraphBuilder.buildAnthropicApi(provider);
        AnthropicChatOptions options = agentGraphBuilder.buildAnthropicOptions(model);
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }
}
