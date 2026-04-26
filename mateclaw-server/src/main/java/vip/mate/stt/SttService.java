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

        // Per-call dispatch trace — without this the only signal that STT
        // is even being attempted is the eventual provider success/failure
        // log, which makes "no audio reached us" indistinguishable from
        // "audio reached us but provider rejected it".
        int bytes = audioData != null ? audioData.length : 0;
        log.info("[STT] dispatch bytes={} fileName={} contentType={} language={} provider={}",
                bytes, fileName, contentType, language,
                config.getSttProvider() != null ? config.getSttProvider() : "auto");

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
        // Language hint resolution order:
        //   1. Caller-supplied request.language (explicit per-call override)
        //   2. UI language from system settings (zh-CN / en-US)
        //   3. null — registry falls back to the language-agnostic order
        // Both registry primary-pick and fallback-candidate ordering use the
        // same hint; without it Paraformer/Whisper would frequently swap
        // priorities mid-fallback for the same conversation.
        //
        // Critical: write the resolved hint BACK into the request, so
        // providers (especially DashScope's run-task language_hints field)
        // see it. Pre-fix, providers got null even though we routed by
        // zh-CN — DashScope's auto-detect would then sit through 2-3s of
        // Chinese audio and emit zero result-generated events.
        String languageHint = request.getLanguage();
        if (languageHint == null || languageHint.isBlank()) {
            languageHint = config.getLanguage();
            if (languageHint != null && !languageHint.isBlank()) {
                request.setLanguage(languageHint);
            }
        }
        SttProvider primary = providerRegistry.resolve(config, languageHint);
        if (primary == null) {
            // Most common cause: no provider has its API key configured. Tell
            // the operator that explicitly so they don't dig through provider
            // logs looking for the real reason.
            log.warn("[STT] no provider available — check DashScope / OpenAI API keys in 模型管理");
            return SttResult.failure("没有可用的 STT Provider，请在模型管理中配置 DashScope 或 OpenAI API Key");
        }
        log.info("[STT] primary provider resolved: {} (language={})", primary.id(), languageHint);

        SttResult result = primary.transcribe(request, config);
        if (result.isSuccess()) {
            log.info("[STT] success via {} ({} chars)", primary.id(), result.getText() != null ? result.getText().length() : 0);
            return result;
        }
        log.warn("[STT] primary {} failed: {}", primary.id(), result.getErrorMessage());

        List<String> errors = new ArrayList<>();
        errors.add(primary.id() + ": " + result.getErrorMessage());

        if (Boolean.TRUE.equals(config.getSttFallbackEnabled())) {
            for (SttProvider fb : providerRegistry.fallbackCandidates(config, primary.id(), languageHint)) {
                log.info("[STT] trying fallback provider: {}", fb.id());
                result = fb.transcribe(request, config);
                if (result.isSuccess()) {
                    log.info("[STT] fallback success via {} ({} chars)", fb.id(),
                            result.getText() != null ? result.getText().length() : 0);
                    return result;
                }
                log.warn("[STT] fallback {} failed: {}", fb.id(), result.getErrorMessage());
                errors.add(fb.id() + ": " + result.getErrorMessage());
            }
        }

        log.error("[STT] all providers failed — errors: {}", errors);
        return SttResult.failure("所有 STT Provider 均失败\n" + String.join("\n", errors));
    }
}
