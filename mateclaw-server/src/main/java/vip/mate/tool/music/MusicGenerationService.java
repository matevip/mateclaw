package vip.mate.tool.music;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 音乐生成服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MusicGenerationService {

    private final SystemSettingService systemSettingService;
    private final MusicProviderRegistry providerRegistry;

    private static final Path UPLOAD_ROOT = Paths.get("data", "chat-uploads");

    public Map<String, Object> generate(String conversationId, MusicGenerationRequest request) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getMusicEnabled())) {
            return Map.of("success", false, "error", "音乐生成功能未启用");
        }

        MusicGenerationResult result = generateWithFallback(request, config);
        if (!result.isSuccess()) {
            return Map.of("success", false, "error", result.getErrorMessage());
        }

        try {
            String fileId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            Path dir = UPLOAD_ROOT.resolve(conversationId);
            Files.createDirectories(dir);
            String fileName = "music_" + fileId + "." + result.getFormat();
            Path filePath = dir.resolve(fileName);
            Files.write(filePath, result.getAudioData());

            String audioUrl = "/api/v1/chat/files/" + conversationId + "/" + fileName;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("audioUrl", audioUrl);
            response.put("contentType", result.getContentType());
            response.put("format", result.getFormat());
            if (result.getLyrics() != null) {
                response.put("lyrics", result.getLyrics());
            }
            return response;
        } catch (IOException e) {
            log.error("[Music] Failed to save audio: {}", e.getMessage());
            return Map.of("success", false, "error", "音频文件保存失败");
        }
    }

    private MusicGenerationResult generateWithFallback(MusicGenerationRequest request, SystemSettingsDTO config) {
        MusicGenerationProvider primary = providerRegistry.resolve(config);
        if (primary == null) return MusicGenerationResult.failure("没有可用的音乐 Provider");

        MusicGenerationResult result = primary.generate(request, config);
        if (result.isSuccess()) return result;

        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getMusicFallbackEnabled())) {
            for (MusicGenerationProvider fb : providerRegistry.fallbackCandidates(config, primary.id())) {
                result = fb.generate(request, config);
                if (result.isSuccess()) return result;
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        return MusicGenerationResult.failure("所有音乐 Provider 均失败\n" + String.join("\n", errors));
    }
}
