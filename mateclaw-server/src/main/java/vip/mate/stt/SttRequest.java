package vip.mate.stt;

import lombok.Builder;
import lombok.Data;

/**
 * STT 语音识别统一请求
 */
@Data
@Builder
public class SttRequest {
    /** 音频二进制数据 */
    private byte[] audioData;
    /** 原始文件名 */
    private String fileName;
    /** MIME 类型（audio/ogg, audio/mpeg 等） */
    private String contentType;
    /** 目标语言（可选，如 "zh", "en"） */
    private String language;
    /** 模型（可选） */
    private String model;
}
