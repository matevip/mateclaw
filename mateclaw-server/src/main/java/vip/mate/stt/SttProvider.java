package vip.mate.stt;

import vip.mate.system.model.SystemSettingsDTO;

/**
 * STT 语音识别提供商接口
 */
public interface SttProvider {
    String id();
    String label();
    boolean requiresCredential();
    int autoDetectOrder();
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * 转写音频
     */
    SttResult transcribe(SttRequest request, SystemSettingsDTO config);
}
