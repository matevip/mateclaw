package vip.mate.tool.image.vision.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
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
 * Shared base for vision providers that hit OpenAI-compatible
 * {@code /chat/completions} endpoints with image-content blocks.
 *
 * <p>DashScope (qwen-vl), Zhipu (glm-v) and Volcengine Ark (doubao
 * vision) all accept the same request shape — a {@code messages} array
 * whose user message contains a {@code text} part and an
 * {@code image_url} part with a base64 data URL. Extracting that
 * shape into one place lets each concrete provider supply only its
 * identity and routing constants.
 *
 * <p>Subclass contract: implement {@link #id()}, {@link #label()},
 * {@link #autoDetectOrder()}, {@link #providerKey()},
 * {@link #defaultBaseUrl()}, and {@link #defaultModel()}. Override
 * {@link #completionsPath()} when the endpoint differs from the
 * default {@code /chat/completions}.
 */
@Slf4j
public abstract class OpenAiCompatibleVisionProvider implements ImageVisionProvider {

    protected static final int CALL_TIMEOUT_MS = 60_000;
    private static final Pattern OFF_TOPIC_PREFIX =
            Pattern.compile("^\\s*\\[OFF-TOPIC\\]\\s*", Pattern.CASE_INSENSITIVE);

    protected final ModelProviderService modelProviderService;
    protected final ObjectMapper objectMapper;

    protected OpenAiCompatibleVisionProvider(ModelProviderService modelProviderService,
                                              ObjectMapper objectMapper) {
        this.modelProviderService = modelProviderService;
        this.objectMapper = objectMapper;
    }

    /** {@code mate_model_provider.provider_id} this vision provider attaches to. */
    protected abstract String providerKey();

    protected abstract String defaultBaseUrl();

    protected abstract String defaultModel();

    /** Override when the endpoint isn't the OpenAI-standard suffix. */
    protected String completionsPath() {
        return "/chat/completions";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.IMAGE_TO_TEXT);
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO settings) {
        try {
            return modelProviderService.isProviderConfigured(providerKey());
        } catch (Exception e) {
            log.debug("[Vision][{}] availability check failed: {}", id(), e.getMessage());
            return false;
        }
    }

    @Override
    public VisionResult caption(VisionRequest request, SystemSettingsDTO settings) {
        ModelProviderEntity provider = loadProvider();
        String apiKey = provider.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new MateClawException("err.wiki.vision.no_provider",
                    label() + " API key is missing");
        }
        String baseUrl = (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank())
                ? defaultBaseUrl()
                : provider.getBaseUrl();
        String model = (request.getPreferModel() == null || request.getPreferModel().isBlank())
                ? defaultModel()
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
            response = HttpRequest.post(baseUrl + completionsPath())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(CALL_TIMEOUT_MS)
                    .execute();
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    label() + " vision call failed: " + e.getMessage());
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        if (response.getStatus() != 200) {
            String errBody = response.body();
            log.warn("[Vision][{}] HTTP {} body={}", id(), response.getStatus(), truncate(errBody, 400));
            throw new MateClawException("err.wiki.vision.provider_failed",
                    label() + " vision returned HTTP " + response.getStatus());
        }

        String text;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            text = root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    label() + " vision response was unparseable: " + e.getMessage());
        }
        if (text.isEmpty()) {
            throw new MateClawException("err.wiki.vision.provider_failed",
                    label() + " vision returned an empty caption");
        }

        Matcher offTopic = OFF_TOPIC_PREFIX.matcher(text);
        boolean isOffTopic = offTopic.find();
        String caption = isOffTopic ? offTopic.replaceFirst("").trim() : text;

        return VisionResult.builder()
                .caption(caption)
                .visibleText(extractVisibleTextHeuristic(caption))
                .offTopic(isOffTopic)
                .providerId(id())
                .model(model)
                .capturedAt(Instant.now())
                .durationMs(durationMs)
                .build();
    }

    private ModelProviderEntity loadProvider() {
        try {
            return modelProviderService.getProviderConfig(providerKey());
        } catch (Exception e) {
            throw new MateClawException("err.wiki.vision.no_provider",
                    label() + " provider is not configured");
        }
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
