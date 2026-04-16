package vip.mate.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelDiscoveryService {

    private final ModelProviderService modelProviderService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Virtual-thread executor for parallel model probing (lightweight, short-lived)
    private static final ExecutorService PROBE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    // Probe concurrency cap — avoid flooding the provider with concurrent ping requests
    private static final int MAX_PROBE_CONCURRENCY = 5;

    // Per-model probe timeout (short; we only need to know "yes/no usable")
    private static final long PROBE_TIMEOUT_SECONDS = 12;

    /**
     * Explicit deny list: model ids listed by DashScope compatible-mode that are known
     * to fail on the native protocol. Updated as we observe new failures.
     */
    private static final Set<String> DASHSCOPE_NATIVE_DENY = Set.of(
            "qwen3.5-max",
            "qwen3.5-plus"
    );

    /**
     * Allow-list prefixes for DashScope models that are known to work on the native
     * protocol. An empty set means "no prefix filter" (we still apply DENY).
     * Extend conservatively as we verify additional families.
     */
    private static final Set<String> DASHSCOPE_NATIVE_ALLOW_PREFIXES = Set.of(
            "qwen-",          // qwen-max / qwen-plus / qwen-turbo / qwen-coder-* / qwen-long
            "qwen2-",         // qwen2 series
            "qwen3-",         // qwen3-max / qwen3-plus / qwen3-coder / qwen3-235b-*
            "qwen-vl-",       // vision-language
            "qwen-audio-",
            "qwen-omni-",
            "deepseek-",      // deepseek-v3.x
            "baichuan",
            "yi-",
            "llama"
    );

    // ==================== 模型发现 ====================

    public DiscoverResult discoverModels(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        if (!Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
            throw new MateClawException("err.llm.discovery_not_supported", "该供应商不支持模型发现: " + providerId);
        }

        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        List<ModelInfoDTO> discovered = fetchRemoteModels(provider, protocol);

        // Layer 2: Protocol-aware allow/deny filtering. The listing endpoint
        // (compatible-mode /v1/models for DashScope) often returns models that
        // the native SDK does not accept — filter them out before the user sees
        // them.
        discovered = applyProtocolFilter(discovered, protocol, providerId);

        // Layer 3: Probe each remaining model with a real runtime-protocol call.
        // This catches any model the allow-list let through but the provider
        // actually rejects at request time. Failed probes are kept in the list
        // but marked probeOk=false so the UI can show a warning badge.
        discovered = probeInParallel(discovered, provider, protocol);

        // De-dupe against already-configured models for the "new" bucket
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());

        // Only propose models that passed the probe (or were not probed) as "new"
        List<ModelInfoDTO> newModels = discovered.stream()
                .filter(m -> !existingIds.contains(m.getId()))
                .filter(m -> !Boolean.FALSE.equals(m.getProbeOk()))
                .toList();

        return new DiscoverResult(discovered, newModels, discovered.size(), newModels.size());
    }

    /**
     * Apply protocol-aware allow/deny filtering to the raw discovery list.
     * <p>
     * Currently only DashScope is filtered: the compatible-mode listing includes
     * many models the native SDK rejects. Other providers pass through unchanged.
     */
    private List<ModelInfoDTO> applyProtocolFilter(List<ModelInfoDTO> discovered,
                                                    ModelProtocol protocol,
                                                    String providerId) {
        if (protocol != ModelProtocol.DASHSCOPE_NATIVE) {
            return discovered;
        }
        int before = discovered.size();
        List<ModelInfoDTO> filtered = discovered.stream()
                .filter(m -> {
                    String id = m.getId();
                    if (id == null || id.isBlank()) return false;
                    String lower = id.toLowerCase();
                    if (DASHSCOPE_NATIVE_DENY.contains(lower)) return false;
                    // Allow if any allowed prefix matches; if allow-list is empty, permit everything
                    if (DASHSCOPE_NATIVE_ALLOW_PREFIXES.isEmpty()) return true;
                    return DASHSCOPE_NATIVE_ALLOW_PREFIXES.stream().anyMatch(lower::startsWith);
                })
                .toList();
        if (filtered.size() < before) {
            log.info("[ModelDiscovery] Filtered {} -> {} DashScope models for provider={} (allow/deny rules)",
                    before, filtered.size(), providerId);
        }
        return filtered;
    }

    /**
     * Probe each discovered model in parallel (bounded concurrency) using the same
     * protocol the runtime will use. Populates {@code probeOk}/{@code probeError}
     * on each DTO; does not remove failed entries so the UI can surface the reason.
     */
    private List<ModelInfoDTO> probeInParallel(List<ModelInfoDTO> discovered,
                                                 ModelProviderEntity provider,
                                                 ModelProtocol protocol) {
        if (discovered.isEmpty()) return discovered;

        // OpenAI ChatGPT has no model-level test, skip probe for it
        if (protocol == ModelProtocol.OPENAI_CHATGPT) return discovered;

        Semaphore sem = new Semaphore(MAX_PROBE_CONCURRENCY);
        List<CompletableFuture<Void>> futures = new ArrayList<>(discovered.size());
        for (ModelInfoDTO dto : discovered) {
            futures.add(CompletableFuture.runAsync(() -> {
                try { sem.acquire(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                try {
                    sendTestPrompt(provider, protocol, dto.getId());
                    dto.setProbeOk(true);
                } catch (Exception e) {
                    dto.setProbeOk(false);
                    dto.setProbeError(shortError(e));
                    log.info("[ModelDiscovery] Probe failed for model={}: {}", dto.getId(), dto.getProbeError());
                } finally {
                    sem.release();
                }
            }, PROBE_EXECUTOR));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PROBE_TIMEOUT_SECONDS * Math.max(1, discovered.size() / MAX_PROBE_CONCURRENCY + 1),
                         TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            log.warn("[ModelDiscovery] Probe batch timeout; {} models may be marked unknown",
                    futures.stream().filter(f -> !f.isDone()).count());
        } catch (Exception e) {
            log.warn("[ModelDiscovery] Probe batch wait failed: {}", e.getMessage());
        }
        long passed = discovered.stream().filter(m -> Boolean.TRUE.equals(m.getProbeOk())).count();
        long failed = discovered.stream().filter(m -> Boolean.FALSE.equals(m.getProbeOk())).count();
        log.info("[ModelDiscovery] Probe results: {} passed, {} failed, {} unknown (of {})",
                passed, failed, discovered.size() - passed - failed, discovered.size());
        return discovered;
    }

    private String shortError(Exception e) {
        String msg = extractErrorMessage(e);
        if (msg == null) return "unknown error";
        // Clip to ~120 chars so the UI tooltip stays usable
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    // ==================== 连接测试 ====================

    public TestResult testConnection(String providerId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            if (Boolean.TRUE.equals(provider.getSupportModelDiscovery())) {
                // 支持模型发现的 provider：调用模型列表 API 验证连接
                fetchRemoteModels(provider, protocol);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, "连接成功");
            } else {
                // 不支持模型发现（如智谱）：用第一个已配置模型发送测试请求
                List<ModelConfigEntity> models = modelConfigService.listModelsByProvider(providerId);
                if (models.isEmpty()) {
                    throw new MateClawException("err.llm.no_model_for_test", "该供应商没有已配置的模型，无法测试连接");
                }
                String testModelId = models.get(0).getModelName();
                String response = sendTestPrompt(provider, protocol, testModelId);
                long latency = System.currentTimeMillis() - start;
                return TestResult.ok(latency, response);
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 单模型测试 ====================

    public TestResult testModel(String providerId, String modelId) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        long start = System.currentTimeMillis();

        try {
            String response = sendTestPrompt(provider, protocol, modelId);
            long latency = System.currentTimeMillis() - start;
            return TestResult.ok(latency, response);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            return TestResult.fail(latency, extractErrorMessage(e));
        }
    }

    // ==================== 批量添加发现的模型 ====================

    public int batchAddModels(String providerId, List<String> modelIds) {
        ModelProviderEntity provider = modelProviderService.getProviderConfig(providerId);
        ModelProtocol protocol = ModelProtocol.fromChatModel(provider.getChatModel());
        Set<String> existingIds = modelConfigService.listModelsByProvider(providerId).stream()
                .map(ModelConfigEntity::getModelName)
                .collect(Collectors.toSet());

        int added = 0;
        int skipped = 0;
        for (String modelId : modelIds) {
            if (modelId == null || modelId.isBlank()) continue;
            if (existingIds.contains(modelId)) continue;
            // Defense-in-depth: never add a DashScope model that is on the native protocol deny list
            if (protocol == ModelProtocol.DASHSCOPE_NATIVE
                    && DASHSCOPE_NATIVE_DENY.contains(modelId.toLowerCase())) {
                log.warn("[ModelDiscovery] Refusing to add {} — on DashScope native deny list", modelId);
                skipped++;
                continue;
            }
            modelConfigService.addModelToProvider(providerId, modelId, modelId, false);
            added++;
        }
        if (skipped > 0) {
            log.info("[ModelDiscovery] batchAddModels: added={}, skipped(deny)={}", added, skipped);
        }
        return added;
    }

    // ==================== 协议分派：模型列表 ====================

    private List<ModelInfoDTO> fetchRemoteModels(ModelProviderEntity provider, ModelProtocol protocol) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> fetchOpenAiCompatibleModels(provider);
            case DASHSCOPE_NATIVE -> fetchDashScopeModels(provider);
            case GEMINI_NATIVE -> fetchGeminiModels(provider);
            case ANTHROPIC_MESSAGES -> fetchAnthropicModels(provider);
            case OPENAI_CHATGPT -> throw new MateClawException("err.llm.chatgpt_no_discovery", "ChatGPT OAuth provider 不支持模型发现");
        };
    }

    private List<ModelInfoDTO> fetchOpenAiCompatibleModels(ModelProviderEntity provider) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }
        String apiKey = provider.getApiKey();

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        RestClient.RequestHeadersSpec<?> spec = client.get().uri("/v1/models");
        if (modelProviderService.hasUsableApiKey(apiKey)) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        // 添加自定义 headers（从 generateKwargs 中读取）
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchDashScopeModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        // DashScope 兼容模式暴露了 OpenAI 兼容的 /v1/models 端点
        RestClient client = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseOpenAiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchGeminiModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        RestClient client = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        String body = client.get()
                .uri("/v1beta/models?key={key}", apiKey.trim())
                .retrieve()
                .body(String.class);
        return parseGeminiModelsResponse(body);
    }

    private List<ModelInfoDTO> fetchAnthropicModels(ModelProviderEntity provider) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();

        String body = client.get().uri("/v1/models").retrieve().body(String.class);
        return parseAnthropicModelsResponse(body);
    }

    // ==================== 协议分派：单模型测试 ====================

    private String sendTestPrompt(ModelProviderEntity provider, ModelProtocol protocol, String modelId) {
        return switch (protocol) {
            case OPENAI_COMPATIBLE -> sendOpenAiTestPrompt(provider, modelId);
            case DASHSCOPE_NATIVE -> sendDashScopeTestPrompt(provider, modelId);
            case GEMINI_NATIVE -> sendGeminiTestPrompt(provider, modelId);
            case ANTHROPIC_MESSAGES -> sendAnthropicTestPrompt(provider, modelId);
            case OPENAI_CHATGPT -> throw new MateClawException("err.llm.chatgpt_no_test", "ChatGPT OAuth provider 不支持模型测试");
        };
    }

    private String sendOpenAiTestPrompt(ModelProviderEntity provider, String modelId) {
        String baseUrl = normalizeBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.llm.base_url_missing", "Base URL 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10,
                "temperature", 0
        );

        // 从 generateKwargs 读取 completionsPath（智谱等用 /chat/completions 而非 /v1/chat/completions）
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String completionsPath = resolveCompletionsPath(baseUrl, kwargs);

        RestClient.RequestHeadersSpec<?> spec = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri(completionsPath)
                .body(requestBody);

        if (modelProviderService.hasUsableApiKey(provider.getApiKey())) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey().trim());
        }
        applyCustomHeaders(spec, kwargs);

        String body = spec.retrieve().body(String.class);
        return extractOpenAiChatContent(body);
    }

    /**
     * Test a DashScope model using the **native** endpoint
     * ({@code /api/v1/services/aigc/text-generation/generation}).
     * <p>
     * This matches the protocol Spring AI Alibaba's {@code DashScopeChatModel} uses
     * at runtime. Using compatible-mode for testing (as the previous implementation
     * did) was the root cause of "test passed but chat fails" — compatible-mode
     * accepts a broader set of model names than the native API does.
     */
    private String sendDashScopeTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.dashscope_key_missing", "DashScope API Key 未配置");
        }

        // DashScope native request shape: input.messages + parameters
        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "input", Map.of(
                        "messages", List.of(Map.of("role", "user", "content", "ping"))
                ),
                "parameters", Map.of(
                        "max_tokens", 1,
                        "temperature", 0,
                        "result_format", "message"
                )
        );

        String body = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build()
                .post()
                .uri("/api/v1/services/aigc/text-generation/generation")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractDashScopeNativeContent(body);
    }

    /**
     * Extract content from DashScope native response:
     * {@code { "output": { "choices": [ { "message": { "content": "..." } } ] } } }
     * Falls back to the raw body preview if the shape differs.
     */
    private String extractDashScopeNativeContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choices = root.path("output").path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isBlank()) return content;
            }
            // Older shape: output.text
            String legacyText = root.path("output").path("text").asText("");
            if (!legacyText.isBlank()) return legacyText;
        } catch (Exception ignored) {}
        return body == null ? "" : (body.length() > 200 ? body.substring(0, 200) : body);
    }

    private String sendGeminiTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.gemini_key_missing", "Gemini API Key 未配置");
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", "请回复：连接正常"))
                )),
                "generationConfig", Map.of("maxOutputTokens", 10, "temperature", 0)
        );

        String body = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
                .post()
                .uri("/v1beta/models/{model}:generateContent?key={key}", modelId, apiKey.trim())
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractGeminiContent(body);
    }

    private String sendAnthropicTestPrompt(ModelProviderEntity provider, String modelId) {
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.llm.anthropic_key_missing", "Anthropic API Key 未配置");
        }

        String baseUrl = StringUtils.hasText(provider.getBaseUrl())
                ? normalizeBaseUrl(provider.getBaseUrl())
                : "https://api.anthropic.com";

        Map<String, Object> requestBody = Map.of(
                "model", modelId,
                "messages", List.of(Map.of("role", "user", "content", "请回复：连接正常")),
                "max_tokens", 10
        );

        String body = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("x-api-key", apiKey.trim())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build()
                .post()
                .uri("/v1/messages")
                .body(requestBody)
                .retrieve()
                .body(String.class);
        return extractAnthropicContent(body);
    }

    // ==================== JSON 解析 ====================

    private List<ModelInfoDTO> parseOpenAiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, id));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 OpenAI 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseGeminiModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> result = new ArrayList<>();
            for (JsonNode node : models) {
                String name = node.path("name").asText("");
                String displayName = node.path("displayName").asText(name);
                // Gemini 返回 "models/gemini-1.5-pro" 格式，去掉 "models/" 前缀
                if (name.startsWith("models/")) {
                    name = name.substring(7);
                }
                if (StringUtils.hasText(name)) {
                    result.add(new ModelInfoDTO(name, displayName));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("解析 Gemini 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ModelInfoDTO> parseAnthropicModelsResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Collections.emptyList();
            }
            List<ModelInfoDTO> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                String displayName = node.path("display_name").asText(id);
                if (StringUtils.hasText(id)) {
                    models.add(new ModelInfoDTO(id, displayName));
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("解析 Anthropic 模型列表失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String extractOpenAiChatContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").path(0).path("message").path("content").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractGeminiContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    private String extractAnthropicContent(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").path(0).path("text").asText("连接正常");
        } catch (Exception e) {
            return "连接正常（响应解析异常）";
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从 generateKwargs 中解析 completionsPath，处理 baseUrl 与路径前缀的重叠。
     * 例如：baseUrl 以 /v4 结尾，completionsPath 为 /chat/completions → 最终 /chat/completions
     *       baseUrl 以 /v1 结尾，completionsPath 为 /v1/chat/completions → 最终 /chat/completions
     */
    private String resolveCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        String path = "/v1/chat/completions";
        if (kwargs != null) {
            Object raw = kwargs.get("completionsPath");
            if (raw instanceof String value && StringUtils.hasText(value)) {
                path = value.trim();
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            }
        }
        // 避免路径重叠：如果 baseUrl 以 /v1 结尾且 path 以 /v1/ 开头，去掉重复
        if (baseUrl != null && baseUrl.endsWith("/v1") && path.startsWith("/v1/")) {
            path = path.substring(3);
        }
        return path;
    }

    private String normalizeBaseUrl(String baseUrl) {
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

    @SuppressWarnings("unchecked")
    private void applyCustomHeaders(RestClient.RequestHeadersSpec<?> spec, Map<String, Object> kwargs) {
        if (kwargs == null) {
            return;
        }
        Object customHeaders = kwargs.get("customHeaders");
        if (customHeaders instanceof Map) {
            ((Map<String, Object>) customHeaders).forEach((key, value) -> {
                if (value != null) {
                    spec.header(key, value.toString());
                }
            });
        }
    }

    private String extractErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "未知错误: " + e.getClass().getSimpleName();
        }
        // 截取合理长度
        if (msg.length() > 200) {
            msg = msg.substring(0, 200) + "...";
        }
        return msg;
    }
}
