package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import vip.mate.llm.chatgpt.ChatGPTChatModel;
import vip.mate.llm.chatgpt.ChatGPTResponsesClient;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;

/**
 * ChatGPT Responses API (the "/codex/responses" endpoint reached via OAuth)
 * doesn't go through Spring AI's standard ChatModel builders — it has its own
 * {@link ChatGPTChatModel} wrapper around {@link ChatGPTResponsesClient}.
 * The {@link RetryTemplate} parameter is ignored because retry happens inside
 * the OAuth-aware client itself.
 */
@Component
public class ChatGPTResponsesChatModelBuilder implements ChatModelBuilder {

    private final ChatGPTResponsesClient chatGPTResponsesClient;

    public ChatGPTResponsesChatModelBuilder(ChatGPTResponsesClient chatGPTResponsesClient) {
        this.chatGPTResponsesClient = chatGPTResponsesClient;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_CHATGPT;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        Double temp = model.getTemperature() != null ? model.getTemperature() : 0.7;
        return new ChatGPTChatModel(chatGPTResponsesClient, model.getModelName(), temp);
    }
}
