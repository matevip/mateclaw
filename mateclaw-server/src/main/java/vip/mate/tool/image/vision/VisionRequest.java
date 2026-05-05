package vip.mate.tool.image.vision;

import lombok.Builder;
import lombok.Data;

/**
 * Input to one image-to-text call.
 *
 * <p>The image is supplied as raw bytes plus a MIME type; callers are
 * responsible for any format conversion before invoking. The optional
 * {@link VisionContext} carries surrounding text so the provider can
 * decide whether the image is on-topic and produce a context-aware
 * caption.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class VisionRequest {

    /** Raw image bytes (PNG / JPEG / WebP / ...). Required. */
    private byte[] imageBytes;

    /** MIME type, e.g. {@code image/png}. Required. */
    private String mimeType;

    /** Optional surrounding-text context for context-aware captioning. */
    private VisionContext context;

    /**
     * Caller hint: when set, ask the provider for this specific model name.
     * Provider may ignore the hint if the model is unsupported.
     */
    private String preferModel;
}
