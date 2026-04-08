package vip.mate.tool.music;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MusicGenerationRequest {
    private String prompt;
    private String lyrics;
    @Builder.Default
    private Boolean instrumental = false;
    private Integer durationSeconds;
    private String model;
    @Builder.Default
    private String format = "mp3";
}
