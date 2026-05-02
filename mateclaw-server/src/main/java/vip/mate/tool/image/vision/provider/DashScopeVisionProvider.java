package vip.mate.tool.image.vision.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.ImageCapability;
import vip.mate.tool.image.vision.ImageVisionProvider;
import vip.mate.tool.image.vision.VisionContext;
import vip.mate.tool.image.vision.VisionRequest;
import vip.mate.tool.image.vision.VisionResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DashScope vision provider — uses qwen-vl-max via the OpenAI-compatible
 * endpoint at {@code /compatible-mode/v1/chat/completions}.
 *
 * <p>Default for the regional production rollout: API keys are typically
 * available (DASHSCOPE_API_KEY is mandatory for the rest of the platform)
 * and per-image cost is the lowest of the supported vendors.
 *
 * <p>Implementation deliberately uses Hutool {@code HttpRequest} rather
 * than building a Spring AI {@code ChatClient} — the call shape is a
 * one-shot request/response that doesn't benefit from the heavyweight
 * agent-style observability / retry / cache machinery in
 * {@code AgentOpenAiCompatibleChatModelBuilder}.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DashScopeVisionProvider implements ImageVisionProvider {

    private static final String PROVIDER_ID = "dashscope-vision";
    private static final String DASHSCOPE_PROVIDER_KEY = "dashscope";
    private static final String DEFAULT_MODEL = "qwen-vl-max";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final int CALL_TIMEOUT_MS = 60_000;
    private static final Pattern OFF_TOPIC_PREFIX = Pattern.compile("^\\s*\\[OFF-TOPIC\\]\\s*", Pattern.CASE_INSENSITIVE);

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String label() {
        return "DashScope qwen-vl";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 10;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.IMAGE_TO_TEXT);
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO settings) {
        try {
            return modelProviderService.isProviderConfigured(DASHSCOPE_PROVIDER_KEY);
        } catch (Exception e) {
            log.debug("[Vision][dashscope] availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public VisionResult caption(VisionRequest request, SystemSettingsDTO settings) {
        ModelProviderEntity provider;
        try {
            provider = modelProviderService.getProviderConfig(DASHSCOPE_PROVIDER_KEY);
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.no_provider",
                    "DashScope provider is not configured");
        }
        String apiKey = provider.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new MateClawException("err.wiki.vision.no_provider",
                    "DashScope API key is missing");
        }
        String baseUrl = (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())
                ? DEFAULT_BASE_URL
                : provider.getBaseUrl();
        String model = (request.getPreferModel() == null || request.getPreferModel().isBlank())
                ? DEFAULT_MODEL
                : request.getPreferModel();

        String prompt = buildPrompt(request.getContext());
        String imageDataUrl = buildDataUrl(request.getMimeType(), request.getImageBytes());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.0);
        body.put("max_tokens", 4096);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        content.addObject().put("type", "text").put("text", prompt);
        ObjectNode imagePart = content.addObject();
        imagePart.put("type", "image_url");
        imagePart.putObject("image_url").put("url", imageDataUrl);

        long startNanos = System.nanoTime();
        HttpResponse response;
        try {
            response = HttpRequest.post(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(CALL_TIMEOUT_MS)
                    .execute();
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    "DashScope vision call failed: " + e.getMessage());
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        if (response.getStatus() != 200) {
            String errBody = response.body();
            log.warn("[Vision][dashscope] HTTP {} body={}", response.getStatus(), truncate(errBody, 400));
            throw new MateClawException("err.wiki.vision.provider_failed",
                    "DashScope vision returned HTTP " + response.getStatus());
        }

        String text;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            text = root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    "DashScope vision response was unparseable: " + e.getMessage());
        }
        if (text.isEmpty()) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    "DashScope vision returned an empty caption");
        }

        Matcher offTopic = OFF_TOPIC_PREFIX.matcher(text);
        boolean isOffTopic = offTopic.find();
        String caption = isOffTopic ? offTopic.replaceFirst("").trim() : text;

        return VisionResult.builder()
                .caption(caption)
                .visibleText(extractVisibleTextHeuristic(caption))
                .offTopic(isOffTopic)
                .providerId(PROVIDER_ID)
                .model(model)
                .capturedAt(Instant.now())
                .durationMs(durationMs)
                .build();
    }

    private String buildPrompt(VisionContext context) {
        if (context != null && context.hasContext()) {
            String template = PromptLoader.loadPrompt("wiki/vision-caption-context-aware");
            String before = (context.getBeforeText() == null || context.getBeforeText().isBlank())
                    ? "(none)" : context.getBeforeText().trim();
            String after = (context.getAfterText() == null || context.getAfterText().isBlank())
                    ? "(none)" : context.getAfterText().trim();
            return template.replace("{before}", before).replace("{after}", after);
        }
        return PromptLoader.loadPrompt("wiki/vision-caption-factual");
    }

    private static String buildDataUrl(String mimeType, byte[] bytes) {
        String mime = (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType;
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Heuristic: pull obvious quoted strings as "visible text". This is a
     * placeholder until a stronger OCR-grade extraction lands; for the
     * factual prompt the model already inlines text verbatim into the
     * caption, so the cache row's caption alone is enough for retrieval.
     */
    private static String extractVisibleTextHeuristic(String caption) {
        Matcher m = Pattern.compile("\"([^\"]{3,})\"").matcher(caption);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.group(1));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
