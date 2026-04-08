package vip.mate.stt;

import lombok.Builder;
import lombok.Data;

/**
 * STT 语音识别结果
 */
@Data
@Builder
public class SttResult {
    private boolean success;
    private String text;
    private String language;
    private String errorMessage;

    public static SttResult success(String text) {
        return SttResult.builder().success(true).text(text).build();
    }

    public static SttResult success(String text, String language) {
        return SttResult.builder().success(true).text(text).language(language).build();
    }

    public static SttResult failure(String errorMessage) {
        return SttResult.builder().success(false).errorMessage(errorMessage).build();
    }
}
