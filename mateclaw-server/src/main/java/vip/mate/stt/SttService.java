package vip.mate.stt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;

import java.util.*;

/**
 * STT 语音识别服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SttService {

    private final SystemSettingService systemSettingService;
    private final SttProviderRegistry providerRegistry;

    public Map<String, Object> transcribe(byte[] audioData, String fileName, String contentType, String language) {
        SystemSettingsDTO config = systemSettingService.getAllSettings();

        if (!Boolean.TRUE.equals(config.getSttEnabled())) {
            return Map.of("success", false, "error", "STT 功能未启用，请在系统设置中开启");
        }

        SttRequest request = SttRequest.builder()
                .audioData(audioData)
                .fileName(fileName)
                .contentType(contentType)
                .language(language)
                .build();

        // Provider 选择 + fallback
        SttResult result = transcribeWithFallback(request, config);

        if (result.isSuccess()) {
            return Map.of("success", true, "text", result.getText(),
                    "language", result.getLanguage() != null ? result.getLanguage() : "");
        } else {
            return Map.of("success", false, "error", result.getErrorMessage());
        }
    }

    private SttResult transcribeWithFallback(SttRequest request, SystemSettingsDTO config) {
        SttProvider primary = providerRegistry.resolve(config);
        if (primary == null) {
            return SttResult.failure("没有可用的 STT Provider，请检查配置");
        }

        SttResult result = primary.transcribe(request, config);
        if (result.isSuccess()) return result;

        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getSttFallbackEnabled())) {
            for (SttProvider fb : providerRegistry.fallbackCandidates(config, primary.id())) {
                log.info("[STT] Trying fallback provider: {}", fb.id());
                result = fb.transcribe(request, config);
                if (result.isSuccess()) return result;
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        return SttResult.failure("所有 STT Provider 均失败\n" + String.join("\n", errors));
    }
}
