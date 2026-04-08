package vip.mate.tool.music.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.music.MusicGenerationProvider;
import vip.mate.tool.music.MusicGenerationRequest;
import vip.mate.tool.music.MusicGenerationResult;

import java.util.List;

/**
 * MiniMax 音乐生成 Provider — music-2.5+
 * <p>
 * 复用视频生成中的 MiniMax API Key。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxMusicProvider implements MusicGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.minimax.io";
    private static final String DEFAULT_MODEL = "music-2.5+";

    @Override public String id() { return "minimax"; }
    @Override public String label() { return "MiniMax Music"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 200; }
    @Override public String defaultModel() { return DEFAULT_MODEL; }
    @Override public List<String> availableModels() { return List.of("music-2.5+", "music-2.5"); }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getMinimaxApiKey());
    }

    @Override
    public MusicGenerationResult generate(MusicGenerationRequest request, SystemSettingsDTO config) {
        try {
            String apiKey = config.getMinimaxApiKey();
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);

            // 构建 prompt（可加时长提示）
            String prompt = request.getPrompt();
            if (request.getDurationSeconds() != null) {
                prompt += " Duration: " + request.getDurationSeconds() + " seconds.";
            }
            body.put("prompt", prompt);

            if (Boolean.TRUE.equals(request.getInstrumental())) {
                body.put("is_instrumental", true);
            }

            if (request.getLyrics() != null && !request.getLyrics().isBlank()) {
                body.put("lyrics", request.getLyrics());
            } else if (!Boolean.TRUE.equals(request.getInstrumental())) {
                body.put("lyrics_optimizer", true);
            }

            body.put("output_format", "url");
            ObjectNode audioSetting = body.putObject("audio_setting");
            audioSetting.put("sample_rate", 44100);
            audioSetting.put("bitrate", 256000);
            audioSetting.put("format", "mp3");

            HttpResponse response = HttpRequest.post(BASE_URL + "/v1/music_generation")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(120_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            int statusCode = result.path("base_resp").path("status_code").asInt(-1);
            if (statusCode != 0) {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax Music] Failed: {}", errMsg);
                return MusicGenerationResult.failure(errMsg);
            }

            // 提取音频 URL 或内联数据
            String audioUrl = result.has("audio_url") ? result.get("audio_url").asText(null)
                    : result.path("data").path("audio_url").asText(null);
            String lyrics = result.has("lyrics") ? result.get("lyrics").asText(null)
                    : result.path("data").path("lyrics").asText(null);

            if (StringUtils.hasText(audioUrl)) {
                // 下载音频
                byte[] audioData = HttpRequest.get(audioUrl).timeout(30_000).execute().bodyBytes();
                log.info("[MiniMax Music] Generated {} bytes audio (model={})", audioData.length, model);
                return MusicGenerationResult.successWithLyrics(audioData, "audio/mpeg", "mp3", lyrics);
            }

            // 尝试 inline audio
            String inlineAudio = result.has("audio") ? result.get("audio").asText(null)
                    : result.path("data").path("audio").asText(null);
            if (StringUtils.hasText(inlineAudio)) {
                byte[] audioData = decodeAudio(inlineAudio);
                return MusicGenerationResult.successWithLyrics(audioData, "audio/mpeg", "mp3", lyrics);
            }

            return MusicGenerationResult.failure("MiniMax 未返回音频数据");

        } catch (Exception e) {
            log.error("[MiniMax Music] Error: {}", e.getMessage(), e);
            return MusicGenerationResult.failure("MiniMax Music 异常: " + e.getMessage());
        }
    }

    private byte[] decodeAudio(String data) {
        // MiniMax 可能返回 hex 或 base64
        if (data.matches("^[0-9a-fA-F]+$") && data.length() % 2 == 0) {
            return hexToBytes(data);
        }
        return java.util.Base64.getDecoder().decode(data);
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
