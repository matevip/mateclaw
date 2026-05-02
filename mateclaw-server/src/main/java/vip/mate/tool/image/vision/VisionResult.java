package vip.mate.tool.image.vision;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Outcome of one successful image-to-text call.
 *
 * <p>Producers must populate {@link #caption}, {@link #providerId},
 * {@link #model} and {@link #capturedAt} at minimum. The other fields
 * are optional and allow callers to render richer UI.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class VisionResult {

    /** 2-4 sentence factual description of the image (primary output). */
    private String caption;

    /** Best-effort recovery of any text rendered inside the image; may be null. */
    private String visibleText;

    /**
     * True when the context-aware prompt determined the image is unrelated
     * to the surrounding text. Caller decides what to do with off-topic
     * captions (downrank / hide / annotate). False (or null) when context
     * was not supplied.
     */
    private boolean offTopic;

    /** SPI-registry id of the provider that produced this result. */
    private String providerId;

    /** Vendor-specific model identifier (e.g. {@code qwen-vl-max}). */
    private String model;

    private Instant capturedAt;

    /** Wall-clock duration of the round-trip, in milliseconds. */
    private long durationMs;
}
