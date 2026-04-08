package vip.mate.tool.music.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.music.MusicGenerationProvider;
import vip.mate.tool.music.MusicGenerationRequest;
import vip.mate.tool.music.MusicGenerationResult;

import java.util.Base64;
import java.util.List;

/**
 * Google Lyria 音乐生成 Provider
 * <p>
 * 使用 Gemini API 的音频生成能力。复用 Google LLM API Key。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleLyriaProvider implements MusicGenerationProvider {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";
    private static final String DEFAULT_MODEL = "lyria-3-clip-preview";

    @Override public String id() { return "google-lyria"; }
    @Override public String label() { return "Google Lyria"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 100; }
    @Override public String defaultModel() { return DEFAULT_MODEL; }
    @Override public List<String> availableModels() { return List.of("lyria-3-clip-preview"); }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        try { return modelProviderService.isProviderConfigured("google"); }
        catch (Exception e) { return false; }
    }

    @Override
    public MusicGenerationResult generate(MusicGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = modelProviderService.getProviderConfig("google").getApiKey();
            if (apiKey == null) return MusicGenerationResult.failure("Google API Key 未配置");

            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

            // 构建 prompt
            StringBuilder prompt = new StringBuilder(request.getPrompt());
            if (Boolean.TRUE.equals(request.getInstrumental())) {
                prompt.append("\nInstrumental only, no vocals.");
            }
            if (request.getLyrics() != null && !request.getLyrics().isBlank()) {
                prompt.append("\nLyrics:\n").append(request.getLyrics());
            }

            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            parts.addObject().put("text", prompt.toString());

            ObjectNode genConfig = body.putObject("generationConfig");
            ArrayNode modalities = genConfig.putArray("responseModalities");
            modalities.add("AUDIO");
            modalities.add("TEXT");

            String url = BASE_URL + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(120_000) // 音乐生成较慢
                    .execute();

            if (response.getStatus() != 200) {
                log.warn("[Google Lyria] Failed: HTTP {} - {}", response.getStatus(), response.body());
                return MusicGenerationResult.failure("Google Lyria 失败: HTTP " + response.getStatus());
            }

            JsonNode result = objectMapper.readTree(response.body());
            return extractAudioFromResponse(result);

        } catch (Exception e) {
            log.error("[Google Lyria] Error: {}", e.getMessage(), e);
            return MusicGenerationResult.failure("Google Lyria 异常: " + e.getMessage());
        }
    }

    private MusicGenerationResult extractAudioFromResponse(JsonNode result) {
        String lyrics = null;
        byte[] audioData = null;
        String mimeType = "audio/mpeg";

        JsonNode candidates = result.path("candidates");
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode parts = candidate.path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            lyrics = part.get("text").asText();
                        }
                        JsonNode inlineData = part.has("inlineData") ? part.get("inlineData") : part.path("inline_data");
                        if (inlineData.has("data")) {
                            mimeType = inlineData.has("mimeType") ? inlineData.get("mimeType").asText("audio/mpeg")
                                    : inlineData.path("mime_type").asText("audio/mpeg");
                            audioData = Base64.getDecoder().decode(inlineData.get("data").asText());
                        }
                    }
                }
            }
        }

        if (audioData == null || audioData.length == 0) {
            return MusicGenerationResult.failure("Google Lyria 未返回音频数据");
        }

        String format = mimeType.contains("wav") ? "wav" : "mp3";
        log.info("[Google Lyria] Generated {} bytes audio", audioData.length);
        return MusicGenerationResult.successWithLyrics(audioData, mimeType, format, lyrics);
    }
}
