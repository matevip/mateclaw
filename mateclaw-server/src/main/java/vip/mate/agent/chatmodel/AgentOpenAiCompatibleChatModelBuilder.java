package vip.mate.agent.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
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
 * Thin strategy adapter for {@link ModelProtocol#OPENAI_COMPATIBLE}.
 * Delegates to {@link AgentGraphBuilder}'s helpers; see
 * {@link AgentDashScopeChatModelBuilder} for the rationale of the delegate
 * pattern and the {@code @Lazy} cycle break.
 */
@Component
public class AgentOpenAiCompatibleChatModelBuilder implements ChatModelBuilder {

    private final AgentGraphBuilder agentGraphBuilder;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    public AgentOpenAiCompatibleChatModelBuilder(
            @Lazy AgentGraphBuilder agentGraphBuilder,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.agentGraphBuilder = agentGraphBuilder;
        this.observationRegistryProvider = observationRegistryProvider;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        OpenAiApi api = agentGraphBuilder.buildOpenAiApi(provider);
        OpenAiChatOptions options = agentGraphBuilder.buildOpenAiOptions(model, provider);
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }
}
