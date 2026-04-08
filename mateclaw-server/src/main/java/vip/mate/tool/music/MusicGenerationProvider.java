package vip.mate.tool.music;

import vip.mate.system.model.SystemSettingsDTO;

import java.util.List;

/**
 * 音乐生成提供商接口
 */
public interface MusicGenerationProvider {
    String id();
    String label();
    boolean requiresCredential();
    int autoDetectOrder();
    boolean isAvailable(SystemSettingsDTO config);
    List<String> availableModels();
    String defaultModel();

    /**
     * 生成音乐（同步返回音频字节）
     */
    MusicGenerationResult generate(MusicGenerationRequest request, SystemSettingsDTO config);
}
