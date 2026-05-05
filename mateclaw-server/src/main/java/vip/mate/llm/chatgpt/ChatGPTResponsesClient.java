package vip.mate.llm.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import vip.mate.exception.MateClawException;
import vip.mate.llm.oauth.OpenAIOAuthService;

import java.util.*;

/**
 * ChatGPT Backend API 客户端 — 调用 chatgpt.com/backend-api/codex/responses（Responses API 格式）
 * 支持 tool calling（function_call）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatGPTResponsesClient {

    private static final String BASE_URL = "https://chatgpt.com/backend-api";
    private static final String RESPONSES_PATH = "/codex/responses";

    private final OpenAIOAuthService oauthService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient = WebClient.create();

    /**
     * 流式调用结果 — 包含文本增量和 tool call 事件
     */
    public record StreamEvent(String type, String content, String toolCallId, String toolName, String toolArgsDelta,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens) {
        public static StreamEvent text(String delta) { return new StreamEvent("text", delta, null, null, null, null, null, null); }
        public static StreamEvent toolCallStart(String callId, String name) { return new StreamEvent("tool_call_start", null, callId, name, null, null, null, null); }
        public static StreamEvent toolCallArgsDelta(String callId, String delta) { return new StreamEvent("tool_call_args_delta", null, callId, null, delta, null, null, null); }
        public static StreamEvent toolCallDone(String callId, String args) { return new StreamEvent("tool_call_done", null, callId, null, args, null, null, null); }
        public static StreamEvent done() { return new StreamEvent("done", null, null, null, null, null, null, null); }
        /** Done event carrying token usage extracted from {@code response.completed.response.usage}. */
        public static StreamEvent done(Integer in, Integer out, Integer total) {
            return new StreamEvent("done", null, null, null, null, in, out, total);
        }
    }

    /**
     * 同步调用 — 收集完整响应（仅文本部分）
     */
    public String call(String model, List<Message> messages, Double temperature, List<ToolDefinition> tools) {
        return streamEvents(model, messages, temperature, tools)
                .filter(e -> "text".equals(e.type()))
                .map(StreamEvent::content)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }

    /**
     * 流式调用 — 返回结构化事件（文本 + tool_call）
     */
    public Flux<StreamEvent> streamEvents(String model, List<Message> messages, Double temperature,
                                          List<ToolDefinition> tools) {
        String accessToken = oauthService.ensureValidAccessToken();
        String accountId = oauthService.getAccountId();
        ObjectNode requestBody = buildRequestBody(model, messages, temperature, tools);
        String bodyJson = requestBody.toString();
        log.info("[ChatGPT] Request: model={}, messages={}, tools={}", model, messages.size(),
                tools != null ? tools.size() : 0);
        log.debug("[ChatGPT] Request body: {}", bodyJson.length() > 2000
                ? bodyJson.substring(0, 2000) + "..." : bodyJson);

        return webClient.post()
                .uri(BASE_URL + RESPONSES_PATH)
                .headers(h -> setHeaders(h, accessToken, accountId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(bodyJson)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> {
                                    log.error("[ChatGPT] API error {}: {}", response.statusCode(), errorBody);
                                    return new MateClawException("ChatGPT API " + response.statusCode() + ": " + errorBody);
                                }))
                .bodyToFlux(String.class)
                .doOnNext(raw -> log.debug("[ChatGPT] SSE raw: {}", raw.length() > 200 ? raw.substring(0, 200) + "..." : raw))
                .filter(line -> !line.isBlank() && !line.equals("[DONE]"))
                .filter(line -> line.startsWith("data:"))
                .map(line -> {
                    if (line.startsWith("data: ")) return line.substring(6);
                    return line.substring(5);
                })
                .filter(line -> !line.isBlank() && !line.equals("[DONE]"))
                .mapNotNull(this::parseSSEEvent)
                .onErrorMap(e -> e instanceof MateClawException ? e
                        : new MateClawException("err.llm.chatgpt_stream_failed", "ChatGPT 流式调用失败: " + e.getMessage()));
    }

    /**
     * 纯文本流（向后兼容）
     */
    public Flux<String> stream(String model, List<Message> messages, Double temperature) {
        return streamEvents(model, messages, temperature, null)
                .filter(e -> "text".equals(e.type()))
                .map(StreamEvent::content);
    }

    // ==================== 请求构建 ====================

    ObjectNode buildRequestBody(String model, List<Message> messages, Double temperature,
                                List<ToolDefinition> tools) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", false);

        // system prompt → instructions
        String systemPrompt = null;
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                systemPrompt = msg.getText();
                break;
            }
        }
        if (systemPrompt != null) {
            body.put("instructions", systemPrompt);
        }

        // 消息 → input 数组（Responses API 格式）
        ArrayNode input = objectMapper.createArrayNode();
        int msgIndex = 0;
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.SYSTEM) continue;

            if (msg.getMessageType() == MessageType.USER) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("role", "user");
                ArrayNode contentArr = objectMapper.createArrayNode();
                ObjectNode textPart = objectMapper.createObjectNode();
                textPart.put("type", "input_text");
                textPart.put("text", msg.getText() != null ? msg.getText() : "");
                contentArr.add(textPart);
                item.set("content", contentArr);
                input.add(item);
            } else if (msg.getMessageType() == MessageType.ASSISTANT) {
                AssistantMessage assistantMsg = (AssistantMessage) msg;

                // 如果 assistant 消息包含 tool calls，需要输出 function_call items
                if (assistantMsg.hasToolCalls()) {
                    // 先输出文本部分（如果有）
                    String text = assistantMsg.getText();
                    if (text != null && !text.isBlank()) {
                        ObjectNode textItem = objectMapper.createObjectNode();
                        textItem.put("type", "message");
                        textItem.put("role", "assistant");
                        textItem.put("id", "msg_" + msgIndex);
                        ArrayNode contentArr = objectMapper.createArrayNode();
                        ObjectNode textPart = objectMapper.createObjectNode();
                        textPart.put("type", "output_text");
                        textPart.put("text", text);
                        contentArr.add(textPart);
                        textItem.set("content", contentArr);
                        input.add(textItem);
                    }
                    // 输出 function_call items
                    for (AssistantMessage.ToolCall tc : assistantMsg.getToolCalls()) {
                        ObjectNode fcItem = objectMapper.createObjectNode();
                        fcItem.put("type", "function_call");
                        fcItem.put("call_id", tc.id());
                        fcItem.put("name", tc.name());
                        fcItem.put("arguments", tc.arguments());
                        input.add(fcItem);
                    }
                } else {
                    ObjectNode item = objectMapper.createObjectNode();
                    item.put("type", "message");
                    item.put("role", "assistant");
                    item.put("id", "msg_" + msgIndex);
                    ArrayNode contentArr = objectMapper.createArrayNode();
                    ObjectNode textPart = objectMapper.createObjectNode();
                    textPart.put("type", "output_text");
                    textPart.put("text", msg.getText() != null ? msg.getText() : "");
                    contentArr.add(textPart);
                    item.set("content", contentArr);
                    input.add(item);
                }
            } else if (msg.getMessageType() == MessageType.TOOL) {
                // Tool result → function_call_output
                ToolResponseMessage toolMsg = (ToolResponseMessage) msg;
                for (ToolResponseMessage.ToolResponse response : toolMsg.getResponses()) {
                    ObjectNode fcoItem = objectMapper.createObjectNode();
                    fcoItem.put("type", "function_call_output");
                    fcoItem.put("call_id", response.id());
                    fcoItem.put("output", response.responseData());
                    input.add(fcoItem);
                }
            }
            msgIndex++;
        }
        body.set("input", input);

        // temperature（推理类模型不支持）
        if (temperature != null && !model.startsWith("gpt-5") && !model.startsWith("o")) {
            body.put("temperature", temperature);
        }

        // tools 数组（Responses API flat format）
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArr = objectMapper.createArrayNode();
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = objectMapper.createObjectNode();
                toolNode.put("type", "function");
                toolNode.put("name", tool.name());
                toolNode.put("description", tool.description());
                try {
                    JsonNode params = objectMapper.readTree(tool.inputSchema());
                    toolNode.set("parameters", params);
                } catch (Exception e) {
                    log.warn("[ChatGPT] Failed to parse tool schema for {}: {}", tool.name(), e.getMessage());
                }
                toolsArr.add(toolNode);
            }
            body.set("tools", toolsArr);
            body.put("tool_choice", "auto");
        }

        // Responses API 特有参数
        ObjectNode text = objectMapper.createObjectNode();
        text.put("verbosity", "medium");
        body.set("text", text);

        ArrayNode include = objectMapper.createArrayNode();
        include.add("reasoning.encrypted_content");
        body.set("include", include);

        return body;
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 SSE 事件 — 支持文本增量和 function_call 事件
     */
    private StreamEvent parseSSEEvent(String eventData) {
        try {
            JsonNode node = objectMapper.readTree(eventData);
            String type = node.path("type").asText("");

            // 文本增量
            if ("response.output_text.delta".equals(type)) {
                String delta = node.path("delta").asText(null);
                return delta != null ? StreamEvent.text(delta) : null;
            }

            // function_call 开始（response.output_item.added with type=function_call）
            if ("response.output_item.added".equals(type)) {
                JsonNode item = node.path("item");
                if ("function_call".equals(item.path("type").asText(""))) {
                    String callId = item.path("call_id").asText("");
                    String name = item.path("name").asText("");
                    log.info("[ChatGPT] Tool call started: name={}, callId={}", name, callId);
                    return StreamEvent.toolCallStart(callId, name);
                }
            }

            // function_call arguments 增量
            if ("response.function_call_arguments.delta".equals(type)) {
                String callId = node.path("call_id").asText("");
                String delta = node.path("delta").asText("");
                return StreamEvent.toolCallArgsDelta(callId, delta);
            }

            // function_call arguments 完成
            if ("response.function_call_arguments.done".equals(type)) {
                String callId = node.path("call_id").asText("");
                String args = node.path("arguments").asText("{}");
                log.info("[ChatGPT] Tool call done: callId={}, args={}", callId,
                        args.length() > 200 ? args.substring(0, 200) + "..." : args);
                return StreamEvent.toolCallDone(callId, args);
            }

            // 完成/结束 — extract usage from response.completed so the agent
            // pipeline can record per-turn token counts (otherwise OAuth turns
            // get logged as 0/0/0). Field shape mirrors the upstream client:
            // input_tokens → prompt, output_tokens → completion, total_tokens
            // falls back to (input + output) when the API omits it.
            if (type.startsWith("response.completed") || type.startsWith("response.done")) {
                JsonNode usage = node.path("response").path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    Integer in = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : null;
                    Integer out = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : null;
                    Integer total = usage.has("total_tokens")
                            ? usage.get("total_tokens").asInt()
                            : (in != null && out != null ? in + out : null);
                    if (in != null || out != null || total != null) {
                        log.debug("[ChatGPT] Usage in response.completed: input={}, output={}, total={}",
                                in, out, total);
                        return StreamEvent.done(in, out, total);
                    }
                }
                return StreamEvent.done();
            }

            // 错误
            if ("response.failed".equals(type)) {
                String error = node.path("response").path("error").path("message").asText("Unknown error");
                log.error("[ChatGPT] Responses API error: {}", error);
                throw new MateClawException("err.llm.chatgpt_error", "ChatGPT 返回错误: " + error);
            }

            return null;
        } catch (MateClawException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Headers ====================

    private void setHeaders(HttpHeaders headers, String accessToken, String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new MateClawException("err.llm.chatgpt_account_missing", "chatgpt-account-id 缺失，请断开后重新 OAuth 登录");
        }
        headers.setBearerAuth(accessToken);
        headers.set("chatgpt-account-id", accountId);
        headers.set("originator", "pi");
        headers.set("OpenAI-Beta", "responses=experimental");
        headers.set("accept", "text/event-stream");
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String release = System.getProperty("os.version", "");
        String arch = System.getProperty("os.arch", "");
        headers.set("User-Agent", "pi (" + os + " " + release + "; " + arch + ")");
    }
}
