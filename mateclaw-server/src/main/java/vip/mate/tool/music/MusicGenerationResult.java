package vip.mate.tool.music;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MusicGenerationResult {
    private boolean success;
    private byte[] audioData;
    private String contentType;
    private String format;
    private String lyrics;
    private String errorMessage;

    public static MusicGenerationResult success(byte[] audioData, String contentType, String format) {
        return MusicGenerationResult.builder().success(true).audioData(audioData).contentType(contentType).format(format).build();
    }

    public static MusicGenerationResult successWithLyrics(byte[] audioData, String contentType, String format, String lyrics) {
        return MusicGenerationResult.builder().success(true).audioData(audioData).contentType(contentType).format(format).lyrics(lyrics).build();
    }

    public static MusicGenerationResult failure(String errorMessage) {
        return MusicGenerationResult.builder().success(false).errorMessage(errorMessage).build();
    }
}
