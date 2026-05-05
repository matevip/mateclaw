package vip.mate.tool.image.vision;

import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.image.ImageCapability;

import java.util.Set;

/**
 * SPI for image-to-text providers.
 *
 * <p>Symmetric counterpart of {@link vip.mate.tool.image.ImageGenerationProvider}.
 * Implementations are auto-discovered as Spring beans and selected by
 * {@link ImageVisionService} according to {@link #autoDetectOrder()}
 * (lower wins) — typically: regional default first, premium fallbacks last.
 *
 * @author MateClaw Team
 */
public interface ImageVisionProvider {

    /**
     * Stable identifier for the SPI registry.
     *
     * <p>Convention: {@code <vendor>-vision} (e.g. {@code dashscope-vision},
     * {@code openai-vision}, {@code claude-vision}).
     */
    String id();

    /** Display label for admin UI. */
    String label();

    /** Whether the provider needs an API key configured. */
    boolean requiresCredential();

    /**
     * Auto-detect priority. Lower wins. Conventional bands:
     * <ul>
     *   <li>10–19: regional default (lowest cost, primary path)</li>
     *   <li>20–29: universal fallback</li>
     *   <li>30+: premium / niche fallback</li>
     * </ul>
     */
    int autoDetectOrder();

    /** Capabilities advertised; today only {@link ImageCapability#IMAGE_TO_TEXT}. */
    Set<ImageCapability> capabilities();

    /** Reports whether the provider is currently usable (key configured + reachable). */
    boolean isAvailable(SystemSettingsDTO settings);

    /**
     * Synchronously caption an image.
     *
     * <p>Throws on failure — the caller (typically {@link ImageVisionService})
     * decides whether to retry / fall back / surface to user.
     *
     * @return non-null populated {@link VisionResult}
     */
    VisionResult caption(VisionRequest request, SystemSettingsDTO settings);
}
