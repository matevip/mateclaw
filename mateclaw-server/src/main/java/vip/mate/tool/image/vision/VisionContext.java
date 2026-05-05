package vip.mate.tool.image.vision;

import lombok.Builder;
import lombok.Data;

/**
 * Surrounding text fed to the vision provider for context-aware captioning.
 *
 * <p>When at least one side is non-blank the provider switches from the
 * factual-only prompt to the context-aware prompt, which asks the model
 * to flag images that look unrelated to the surrounding text (so callers
 * can mark them off-topic in downstream pipelines).
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class VisionContext {

    /** Up to ~500 chars of text that appeared immediately before the image. */
    private String beforeText;

    /** Up to ~500 chars of text that appeared immediately after the image. */
    private String afterText;

    public boolean hasContext() {
        return (beforeText != null && !beforeText.isBlank())
                || (afterText != null && !afterText.isBlank());
    }
}
