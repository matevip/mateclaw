package vip.mate.tool.music.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.llm.event.ModelConfigChangedEvent;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.music.MusicGenerationProvider;
import vip.mate.tool.music.MusicGenerationRequest;
import vip.mate.tool.music.MusicGenerationResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiniMax 音乐生成 Provider — music-2.5+.
 * <p>
 * Credential resolution order (first match wins):
 * <ol>
 *   <li>{@code mate_model_provider} entry with id {@code minimax-cn} — China region
 *       (api.minimaxi.com). The base URL declared for the LLM endpoint typically
 *       ends in {@code /anthropic}; we strip that and append the music path.</li>
 *   <li>{@code mate_model_provider} entry with id {@code minimax} — international
 *       region (api.minimax.io).</li>
 *   <li>Legacy {@code mate_system_setting.minimaxApiKey} — only kept for
 *       backwards compatibility with the pre-RFC-202605 single-key setup; defaults
 *       to the international endpoint.</li>
 * </ol>
 * The China and international MiniMax APIs use different keys and different hosts;
 * sourcing credentials from the unified LLM provider table avoids the prior bug
 * where users had to configure the same key in two places (and the music provider
 * silently used the wrong region).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniMaxMusicProvider implements MusicGenerationProvider {

    private final ObjectMapper objectMapper;
    private final ModelProviderService modelProviderService;

    private static final String DEFAULT_BASE_URL = "https://api.minimax.io";
    private static final String DEFAULT_MODEL = "music-2.5+";
    private static final String[] LLM_PROVIDER_IDS = {"minimax-cn", "minimax"};

    /** Cache TTL — credentials rarely change at runtime; 60s is generous and
     *  invalidated immediately on {@link ModelConfigChangedEvent}. Without this
     *  every music task hit {@code SELECT mate_model_provider WHERE provider_id=?}
     *  4-6 times (registry probe + worker re-resolve + per-call resolve). */
    private static final long CACHE_TTL_NANOS = 60L * 1_000_000_000L;

    private final AtomicReference<CachedCredentials> cache = new AtomicReference<>();

    private record CachedCredentials(Credentials creds, long expiresAtNanos) {}

    @Override public String id() { return "minimax"; }
    @Override public String label() { return "MiniMax Music"; }
    @Override public boolean requiresCredential() { return true; }
    @Override public int autoDetectOrder() { return 200; }
    @Override public String defaultModel() { return DEFAULT_MODEL; }
    @Override public List<String> availableModels() { return List.of("music-2.5+", "music-2.5"); }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return resolveCredentials(config) != null;
    }

    private record Credentials(String apiKey, String baseUrl) {}

    private Credentials resolveCredentials(SystemSettingsDTO config) {
        CachedCredentials hit = cache.get();
        long now = System.nanoTime();
        if (hit != null && now < hit.expiresAtNanos()) {
            return hit.creds();
        }
        Credentials fresh = resolveCredentialsUncached(config);
        cache.set(new CachedCredentials(fresh, now + CACHE_TTL_NANOS));
        return fresh;
    }

    private Credentials resolveCredentialsUncached(SystemSettingsDTO config) {
        for (String providerId : LLM_PROVIDER_IDS) {
            try {
                if (!modelProviderService.isProviderConfigured(providerId)) {
                    continue;
                }
                ModelProviderEntity p = modelProviderService.getProviderConfig(providerId);
                if (StringUtils.hasText(p.getApiKey())) {
                    return new Credentials(p.getApiKey(), normalizeBaseUrl(p.getBaseUrl()));
                }
            } catch (Exception ignore) {
                // try next id
            }
        }
        String legacyKey = config.getMinimaxApiKey();
        if (StringUtils.hasText(legacyKey)) {
            return new Credentials(legacyKey, DEFAULT_BASE_URL);
        }
        return null;
    }

    /** Drop the credential cache when any provider config changes — keeps the
     *  60s TTL safe under the user-edits-key-mid-task scenario. */
    @EventListener
    public void onModelConfigChanged(ModelConfigChangedEvent event) {
        cache.set(null);
    }

    /**
     * Reduce any configured base URL to its scheme+host origin (e.g.
     * {@code https://api.minimaxi.com/anthropic} → {@code https://api.minimaxi.com}).
     * The music API lives at {@code /v1/music_generation} regardless of which
     * upstream path the LLM client uses.
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) return DEFAULT_BASE_URL;
        try {
            java.net.URI uri = java.net.URI.create(baseUrl.trim());
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                return DEFAULT_BASE_URL;
            }
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (IllegalArgumentException e) {
            return DEFAULT_BASE_URL;
        }
    }

    @Override
    public MusicGenerationResult generate(MusicGenerationRequest request, SystemSettingsDTO config) {
        try {
            Credentials creds = resolveCredentials(config);
            if (creds == null) {
                return MusicGenerationResult.failure("MiniMax 凭据未配置（在「模型与凭据」中配置 minimax-cn 或 minimax）");
            }
            String apiKey = creds.apiKey();
            String baseUrl = creds.baseUrl();
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

            HttpResponse response = HttpRequest.post(baseUrl + "/v1/music_generation")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(300_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            int statusCode = result.path("base_resp").path("status_code").asInt(-1);
            if (statusCode != 0) {
                String errMsg = result.path("base_resp").path("status_msg").asText("未知错误");
                log.warn("[MiniMax Music] Failed: {}", errMsg);
                return MusicGenerationResult.failure(errMsg);
            }

            // Response shape varies between regions:
            //   - Some responses put the CDN URL in {audio_url} or {data.audio_url}.
            //   - Others put it in {audio} or {data.audio} (the field nominally for
            //     inline hex/base64 binary). Earlier versions of this code treated
            //     {data.audio} as inline data unconditionally and base64-decoded a
            //     URL, throwing "Illegal base64 character 3a" on the ':' in https://.
            // Fix: resolve a single audioCandidate, then route to URL download or
            // binary decode based on whether it parses as http(s) URL.
            String audioCandidate = firstNonBlank(
                    nullableText(result, "audio"),
                    nullableText(result.path("data"), "audio"));
            String audioUrl = firstNonBlank(
                    nullableText(result, "audio_url"),
                    nullableText(result.path("data"), "audio_url"));
            if (audioUrl == null && isLikelyRemoteUrl(audioCandidate)) {
                audioUrl = audioCandidate;
            }
            String inlineAudio = isLikelyRemoteUrl(audioCandidate) ? null : audioCandidate;
            String lyrics = firstNonBlank(
                    nullableText(result, "lyrics"),
                    nullableText(result.path("data"), "lyrics"));

            if (audioUrl != null) {
                byte[] audioData = HttpRequest.get(audioUrl).timeout(30_000).execute().bodyBytes();
                log.info("[MiniMax Music] Generated {} bytes audio via URL (model={})", audioData.length, model);
                return MusicGenerationResult.successWithLyrics(audioData, "audio/mpeg", "mp3", lyrics);
            }

            if (StringUtils.hasText(inlineAudio)) {
                byte[] audioData = decodeAudio(inlineAudio);
                log.info("[MiniMax Music] Generated {} bytes audio inline (model={})", audioData.length, model);
                return MusicGenerationResult.successWithLyrics(audioData, "audio/mpeg", "mp3", lyrics);
            }

            log.warn("[MiniMax Music] Response missing audio output. Body keys: {}",
                    iteratorToString(result.fieldNames()));
            return MusicGenerationResult.failure("MiniMax 未返回音频数据");

        } catch (Exception e) {
            log.error("[MiniMax Music] Error: {}", e.getMessage(), e);
            return MusicGenerationResult.failure("MiniMax Music 异常: " + e.getMessage());
        }
    }

    private static String nullableText(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static boolean isLikelyRemoteUrl(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        return trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8);
    }

    private static String iteratorToString(java.util.Iterator<String> it) {
        StringBuilder sb = new StringBuilder("[");
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(',');
        }
        return sb.append(']').toString();
    }

    private byte[] decodeAudio(String data) {
        String trimmed = data.trim();
        if (trimmed.matches("^[0-9a-fA-F]+$") && trimmed.length() % 2 == 0) {
            return hexToBytes(trimmed);
        }
        return java.util.Base64.getDecoder().decode(trimmed);
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
