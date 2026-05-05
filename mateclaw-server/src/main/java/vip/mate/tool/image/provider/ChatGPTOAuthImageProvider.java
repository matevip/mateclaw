package vip.mate.tool.image.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.llm.oauth.OpenAIOAuthService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Image generation provider that drives {@code gpt-image-2} through the
 * ChatGPT subscription OAuth path — i.e., the user's ChatGPT Plus / Pro
 * quota instead of an OpenAI API key.
 *
 * <p>Mechanism: the Codex Responses API (running at
 * {@code https://chatgpt.com/backend-api/codex/responses}) hosts a tool of
 * type {@code image_generation}. We invoke a small chat-host model
 * (default {@code gpt-5.4}) and force {@code tool_choice} to the
 * image_generation tool, so the chat model never produces text — it just
 * triggers the image tool, which runs {@code gpt-image-2} server-side and
 * streams the result back as base64 PNG.
 *
 * <p>Required headers (Cloudflare in front of the codex backend rejects
 * other clients):
 * <ul>
 *   <li>{@code Authorization: Bearer <oauth_access_token>} (auto-refreshed)</li>
 *   <li>{@code originator: codex_cli_rs}</li>
 *   <li>{@code User-Agent: codex_cli_rs/0.0.0 (MateClaw)}</li>
 *   <li>{@code ChatGPT-Account-ID: <chatgpt_account_id>} parsed from JWT</li>
 * </ul>
 *
 * <p>Response is server-sent events; we parse partial-image and final
 * {@code response.output_item.done} frames and return the latest base64
 * payload as a {@code data:image/png;base64,...} URL — same shape the
 * existing {@link OpenAiImageProvider} uses for its {@code gpt-image-2}
 * branch, so the frontend renderer can stay unchanged.
 *
 * <p><strong>Caveat:</strong> the upstream Codex CLI app is gated behind
 * an undocumented model allow-list. The {@code gpt-5.4 + image_generation}
 * pairing works today but may flip to 403 if OpenAI tightens the list;
 * users can fall back to the API-key {@link OpenAiImageProvider}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatGPTOAuthImageProvider implements ImageGenerationProvider {

    private final OpenAIOAuthService oauthService;
    private final ObjectMapper objectMapper;

    private static final String CODEX_RESPONSES_URL =
            "https://chatgpt.com/backend-api/codex/responses";

    /** Real image API model. Three quality tiers map onto this single model. */
    private static final String IMAGE_API_MODEL = "gpt-image-2";

    private static final List<String> GPT_IMAGE_2_TIERS =
            List.of("gpt-image-2-low", "gpt-image-2-medium", "gpt-image-2-high");

    private static final List<String> GPT_IMAGE_2_SIZES =
            List.of("1024x1024", "1536x1024", "1024x1536");

    /** Chat host model that triggers the image_generation tool. Configurable
     * because OpenAI rotates which slugs accept the tool. */
    @Value("${mateclaw.image.chatgpt-oauth.chat-host-model:gpt-5.4}")
    private String chatHostModel;

    /** Default quality tier when the request leaves it unspecified. */
    @Value("${mateclaw.image.chatgpt-oauth.default-quality:medium}")
    private String defaultQuality;

    /** Read timeout for high-tier generations (~2 min upstream worst case). */
    @Value("${mateclaw.image.chatgpt-oauth.timeout-ms:240000}")
    private int timeoutMs;

    @Override
    public String id() {
        return "openai-chatgpt";
    }

    @Override
    public String label() {
        return "ChatGPT (OAuth) — gpt-image-2";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        // Sit just below the API-key OpenAI provider so a configured OAuth
        // user prefers their subscription quota over per-token API spend
        // when both providers are available.
        return 195;
    }

    @Override
    public Set<ImageCapability> capabilities() {
        return Set.of(ImageCapability.TEXT_TO_IMAGE);
    }

    @Override
    public ImageProviderCapabilities detailedCapabilities() {
        return ImageProviderCapabilities.builder()
                .modes(capabilities())
                .supportedSizes(new ArrayList<>(GPT_IMAGE_2_SIZES))
                .aspectRatios(List.of("1:1", "9:16", "16:9"))
                .maxCount(1)
                .defaultModel("gpt-image-2-medium")
                .models(GPT_IMAGE_2_TIERS)
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try {
            return oauthService.getStatus().connected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config) {
        String token;
        try {
            token = oauthService.ensureValidAccessToken();
        } catch (MateClawException e) {
            log.warn("[ChatGPT OAuth Image] auth failed: {}", e.getMessage());
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
        String accountId = oauthService.getAccountId();

        String quality = qualityForRequest(request);
        String size = normalizeSize(request.getSize(), request.getAspectRatio());

        String body = buildResponsesBody(request.getPrompt(), size, quality);

        try {
            HttpRequest req = HttpRequest.post(CODEX_RESPONSES_URL)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("User-Agent", "codex_cli_rs/0.0.0 (MateClaw)")
                    .header("originator", "codex_cli_rs")
                    .body(body)
                    .timeout(timeoutMs);
            if (accountId != null && !accountId.isBlank()) {
                req.header("ChatGPT-Account-ID", accountId);
            }

            HttpResponse response = req.execute();
            if (response.getStatus() != 200) {
                String errMsg = "HTTP " + response.getStatus() + ": " + truncate(response.body(), 240);
                log.warn("[ChatGPT OAuth Image] non-200 from codex: {}", errMsg);
                return ImageSubmitResult.failure(id(), errMsg);
            }

            String pngB64 = extractFinalImageFromSseBody(response.body());
            if (pngB64 == null || pngB64.isBlank()) {
                log.warn("[ChatGPT OAuth Image] response had no image_generation_call result");
                return ImageSubmitResult.failure(id(),
                        "ChatGPT 响应中未找到 image_generation_call 结果");
            }

            log.info("[ChatGPT OAuth Image] generated 1 image (quality={}, size={})", quality, size);
            return ImageSubmitResult.syncSuccess(id(),
                    List.of("data:image/png;base64," + pngB64));
        } catch (Exception e) {
            log.error("[ChatGPT OAuth Image] submit error: {}", e.getMessage(), e);
            return ImageSubmitResult.failure(id(), e.getMessage());
        }
    }

    // ==================== request body construction =======================

    /**
     * Build the Responses API JSON body. The chat host model is forced into
     * a single image_generation tool call via {@code tool_choice}, so we
     * never round-trip text generation.
     */
    String buildResponsesBody(String prompt, String size, String quality) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", chatHostModel);
        root.put("store", false);
        root.put("instructions",
                "You are an image generation assistant. Always call the image_generation tool. "
                        + "Do not produce any text response.");

        // input: a single user message carrying the prompt
        ArrayNode input = root.putArray("input");
        ObjectNode msg = input.addObject();
        msg.put("type", "message");
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        ObjectNode part = content.addObject();
        part.put("type", "input_text");
        part.put("text", prompt == null ? "" : prompt);

        // tools: single image_generation entry pinned to gpt-image-2
        ArrayNode tools = root.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("type", "image_generation");
        tool.put("model", IMAGE_API_MODEL);
        tool.put("size", size);
        tool.put("quality", quality);
        tool.put("output_format", "png");
        tool.put("background", "opaque");
        tool.put("partial_images", 1);

        // tool_choice: force the model to invoke the tool
        ObjectNode choice = root.putObject("tool_choice");
        choice.put("type", "allowed_tools");
        choice.put("mode", "required");
        ArrayNode allowed = choice.putArray("tools");
        ObjectNode allowedTool = allowed.addObject();
        allowedTool.put("type", "image_generation");

        return root.toString();
    }

    // ==================== SSE stream parsing ==============================

    /**
     * Parse the Server-Sent Events body returned by {@code /codex/responses}
     * and extract the latest base64 PNG. Prefers the
     * {@code response.output_item.done} frame's {@code item.result} (the
     * final image); falls back to the latest
     * {@code response.image_generation_call.partial_image} frame so a
     * connection truncated near the end still surfaces something.
     *
     * <p>Package-private for unit-testability.
     */
    String extractFinalImageFromSseBody(String body) {
        if (body == null || body.isBlank()) return null;

        String finalResult = null;
        String latestPartial = null;

        // SSE frames are separated by blank lines. Inside each frame, lines
        // beginning with "data:" carry JSON (possibly multi-line if the
        // server splits a payload, though OpenAI doesn't).
        String[] frames = body.split("\\r?\\n\\r?\\n");
        for (String frame : frames) {
            if (frame.isBlank()) continue;
            StringBuilder dataBuf = new StringBuilder();
            for (String line : frame.split("\\r?\\n")) {
                String stripped = line.trim();
                if (stripped.startsWith("data:")) {
                    if (dataBuf.length() > 0) dataBuf.append('\n');
                    dataBuf.append(stripped.substring(5).trim());
                }
            }
            if (dataBuf.length() == 0) continue;
            String json = dataBuf.toString();
            if ("[DONE]".equals(json)) continue;

            try {
                JsonNode node = objectMapper.readTree(json);
                String type = node.path("type").asText("");
                if ("response.image_generation_call.partial_image".equals(type)) {
                    String partial = node.path("partial_image_b64").asText(null);
                    if (partial != null && !partial.isBlank()) latestPartial = partial;
                } else if ("response.output_item.done".equals(type)) {
                    JsonNode item = node.path("item");
                    if ("image_generation_call".equals(item.path("type").asText(""))) {
                        String result = item.path("result").asText(null);
                        if (result != null && !result.isBlank()) finalResult = result;
                    }
                } else if ("response.completed".equals(type)) {
                    // Some replies put the final image only in response.completed.output[]
                    JsonNode output = node.path("response").path("output");
                    if (output.isArray()) {
                        for (JsonNode it : output) {
                            if ("image_generation_call".equals(it.path("type").asText(""))) {
                                String result = it.path("result").asText(null);
                                if (result != null && !result.isBlank()) finalResult = result;
                            }
                        }
                    }
                }
            } catch (Exception parseErr) {
                // Malformed frame — keep going. Real frames are JSON; the
                // occasional comment/heartbeat frame harmlessly falls here.
                log.debug("[ChatGPT OAuth Image] skipping unparseable SSE data: {}", parseErr.getMessage());
            }
        }
        return finalResult != null ? finalResult : latestPartial;
    }

    // ==================== helpers =========================================

    /**
     * Resolve the quality tier for this request. Uses the requested model
     * when it's a virtual {@code gpt-image-2-{tier}} id, else
     * {@link #defaultQuality}.
     */
    String qualityForRequest(ImageGenerationRequest request) {
        String requested = request.getModel();
        // Guard the contains() call: List.of(...) throws NPE on a null arg,
        // and the request model is routinely unset.
        if (requested != null && GPT_IMAGE_2_TIERS.contains(requested)) {
            return switch (requested) {
                case "gpt-image-2-low" -> "low";
                case "gpt-image-2-high" -> "high";
                default -> "medium";
            };
        }
        return normalizeQuality(defaultQuality);
    }

    private static String normalizeQuality(String q) {
        if (q == null) return "medium";
        return switch (q.toLowerCase()) {
            case "low", "medium", "high" -> q.toLowerCase();
            default -> "medium";
        };
    }

    /**
     * Pick a {@code gpt-image-2}-supported size from the requested {@code size}
     * + {@code aspectRatio}. Mirrors the gpt-image-2 branch of
     * {@link OpenAiImageProvider#normalizeSize}.
     */
    String normalizeSize(String size, String aspectRatio) {
        if (size != null && !size.isBlank() && GPT_IMAGE_2_SIZES.contains(size)) {
            return size;
        }
        if (aspectRatio != null) {
            return switch (aspectRatio) {
                case "9:16", "2:3", "3:4" -> "1024x1536";
                case "16:9", "3:2", "4:3" -> "1536x1024";
                default -> "1024x1024";
            };
        }
        return "1024x1024";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
