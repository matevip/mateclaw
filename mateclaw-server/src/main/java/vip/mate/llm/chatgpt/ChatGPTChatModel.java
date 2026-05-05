package vip.mate.llm.chatgpt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * ChatGPT 会员模型 — 实现 Spring AI ChatModel 接口。
 * 支持 tool calling：从 Prompt 的 ChatOptions 中提取 toolCallbacks，
 * 传递给 ChatGPTResponsesClient，并将响应中的 function_call 转换为 ToolCall。
 */
@Slf4j
public class ChatGPTChatModel implements ChatModel {

    private final ChatGPTResponsesClient client;
    private final String modelName;
    private final Double temperature;

    public ChatGPTChatModel(ChatGPTResponsesClient client, String modelName, Double temperature) {
        this.client = client;
        this.modelName = modelName;
        this.temperature = temperature;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String model = resolveModel(prompt);
        Double temp = resolveTemperature(prompt);
        List<ToolDefinition> toolDefs = extractToolDefinitions(prompt);

        log.debug("[ChatGPT] call: model={}, messages={}, tools={}", model, messages.size(), toolDefs.size());
        String content = client.call(model, messages, temp, toolDefs);

        Generation generation = new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason("stop").build());
        return new ChatResponse(List.of(generation),
                ChatResponseMetadata.builder().model(model).build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        String model = resolveModel(prompt);
        Double temp = resolveTemperature(prompt);
        List<ToolDefinition> toolDefs = extractToolDefinitions(prompt);

        log.debug("[ChatGPT] stream: model={}, messages={}, tools={}", model, messages.size(), toolDefs.size());

        // 状态：累积 tool call arguments
        Map<String, String> toolCallNames = new LinkedHashMap<>();
        Map<String, StringBuilder> toolCallArgs = new LinkedHashMap<>();

        return client.streamEvents(model, messages, temp, toolDefs)
                .mapNotNull(event -> {
                    switch (event.type()) {
                        case "text" -> {
                            Generation gen = new Generation(new AssistantMessage(event.content()),
                                    ChatGenerationMetadata.builder().finishReason(null).build());
                            return new ChatResponse(List.of(gen),
                                    ChatResponseMetadata.builder().model(model).build());
                        }
                        case "tool_call_start" -> {
                            // 创建初始 ToolCall（空 arguments），让 NodeStreamingChatHelper 创建 accumulator
                            toolCallNames.put(event.toolCallId(), event.toolName());
                            toolCallArgs.put(event.toolCallId(), new StringBuilder());
                            List<AssistantMessage.ToolCall> startCalls = List.of(
                                    new AssistantMessage.ToolCall(event.toolCallId(), "function", event.toolName(), "")
                            );
                            AssistantMessage startMsg = AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(startCalls)
                                    .build();
                            Generation startGen = new Generation(startMsg,
                                    ChatGenerationMetadata.builder().finishReason(null).build());
                            return new ChatResponse(List.of(startGen),
                                    ChatResponseMetadata.builder().model(model).build());
                        }
                        case "tool_call_args_delta" -> {
                            // 增量追加 arguments（通过空 id 的 ToolCall 让 accumulator 追加）
                            StringBuilder sb = toolCallArgs.get(event.toolCallId());
                            if (sb != null) sb.append(event.toolArgsDelta());
                            List<AssistantMessage.ToolCall> deltaCalls = List.of(
                                    new AssistantMessage.ToolCall("", "function", "", event.toolArgsDelta())
                            );
                            AssistantMessage deltaMsg = AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(deltaCalls)
                                    .build();
                            Generation deltaGen = new Generation(deltaMsg,
                                    ChatGenerationMetadata.builder().finishReason(null).build());
                            return new ChatResponse(List.of(deltaGen),
                                    ChatResponseMetadata.builder().model(model).build());
                        }
                        case "tool_call_done" -> {
                            // 不需要再发一次完整的 — accumulator 已经有了
                            return null;
                        }
                        case "done" -> {
                            // Emit a final empty-content ChatResponse carrying the
                            // usage metadata so NodeStreamingChatHelper can record
                            // per-turn token counts. Without this the OAuth path's
                            // turns log 0/0/0 (the client only knows about Usage
                            // when it's attached to a ChatResponse.metadata).
                            if (event.inputTokens() != null || event.outputTokens() != null
                                    || event.totalTokens() != null) {
                                int in = event.inputTokens() != null ? event.inputTokens() : 0;
                                int out = event.outputTokens() != null ? event.outputTokens() : 0;
                                int total = event.totalTokens() != null ? event.totalTokens() : (in + out);
                                Usage usage = new DefaultUsage(in, out, total);
                                Generation usageGen = new Generation(new AssistantMessage(""),
                                        ChatGenerationMetadata.builder().finishReason("stop").build());
                                return new ChatResponse(List.of(usageGen),
                                        ChatResponseMetadata.builder().model(model).usage(usage).build());
                            }
                            return null;
                        }
                        default -> { return null; }
                    }
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build();
    }

    /**
     * 从 Prompt 的 ChatOptions 中提取 ToolDefinition 列表
     */
    private List<ToolDefinition> extractToolDefinitions(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) return List.of();

        // ToolCallingChatOptions 或 OpenAiChatOptions 都可能包含 toolCallbacks
        List<ToolCallback> callbacks = null;
        if (options instanceof ToolCallingChatOptions tcOpts) {
            callbacks = tcOpts.getToolCallbacks();
        } else {
            // 尝试反射获取（Spring AI 的 OpenAiChatOptions 也有 toolCallbacks）
            try {
                var method = options.getClass().getMethod("getToolCallbacks");
                @SuppressWarnings("unchecked")
                var result = (List<ToolCallback>) method.invoke(options);
                callbacks = result;
            } catch (Exception ignored) {
                // 不支持 tool callbacks
            }
        }

        if (callbacks == null || callbacks.isEmpty()) return List.of();

        return callbacks.stream()
                .map(ToolCallback::getToolDefinition)
                .toList();
    }

    private String resolveModel(Prompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            return prompt.getOptions().getModel();
        }
        return modelName;
    }

    private Double resolveTemperature(Prompt prompt) {
        if (prompt.getOptions() != null && prompt.getOptions().getTemperature() != null) {
            return prompt.getOptions().getTemperature();
        }
        return temperature;
    }
}
