package vip.mate.tool.image.vision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.system.service.SystemSettingService;
import vip.mate.wiki.metrics.WikiMetrics;
import vip.mate.wiki.model.WikiImageCaptionCacheEntity;
import vip.mate.wiki.service.WikiImageCaptionCacheService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Routing + caching coordinator for the image-to-text pipeline.
 *
 * <p>End-to-end flow:
 * <ol>
 *   <li>Short-circuit when the {@code wiki.ocr.enabled} feature flag is off.</li>
 *   <li>Hash the image bytes (SHA-256) and probe the caption cache. A cache
 *       hit returns immediately and bumps the row's {@code hit_count} as a
 *       side effect.</li>
 *   <li>On miss, walk providers in {@link ImageVisionProvider#autoDetectOrder()}
 *       order. The first available provider that returns a non-null caption
 *       wins. Failures are logged and the next provider is tried.</li>
 *   <li>Persist the winning caption to the cache (race-tolerant) and emit
 *       success metrics.</li>
 *   <li>If every provider fails, throw a {@link MateClawException} carrying
 *       the i18n key {@code err.wiki.vision.all_failed}.</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageVisionService {

    private static final String FEATURE_FLAG = "wiki.ocr.enabled";

    private final List<ImageVisionProvider> providers;
    private final WikiImageCaptionCacheService cacheService;
    private final SystemSettingService systemSettingService;
    private final FeatureFlagService featureFlagService;
    private final WikiMetrics metrics;

    /**
     * Captions an image, going through cache → provider chain.
     *
     * @throws MateClawException with key {@code err.wiki.vision.disabled} when the feature flag is off
     * @throws MateClawException with key {@code err.wiki.vision.no_provider} when no provider is available
     * @throws MateClawException with key {@code err.wiki.vision.all_failed} when every provider failed
     */
    public VisionResult caption(VisionRequest request) {
        if (request == null || request.getImageBytes() == null
                || request.getImageBytes().length == 0) {
            throw new IllegalArgumentException("VisionRequest with image bytes is required");
        }
        if (!featureFlagService.isEnabled(FEATURE_FLAG)) {
            throw new MateClawException("err.wiki.vision.disabled",
                    "Image vision pipeline is currently disabled");
        }

        String sha256 = sha256Hex(request.getImageBytes());

        // 1. Cache check.
        Optional<WikiImageCaptionCacheEntity> cached = cacheService.lookup(sha256);
        if (cached.isPresent()) {
            metrics.recordVisionCacheHit(true);
            log.debug("[Vision] cache hit sha={}", shortSha(sha256));
            return fromEntity(cached.get());
        }
        metrics.recordVisionCacheHit(false);

        // 2. Provider walk.
        SystemSettingsDTO settings = systemSettingService.getSettings();
        List<ImageVisionProvider> available = providers.stream()
                .filter(p -> {
                    try {
                        return p.isAvailable(settings);
                    } catch (Exception e) {
                        log.debug("[Vision] availability check threw for provider={}: {}",
                                p.id(), e.getMessage());
                        return false;
                    }
                })
                .sorted(Comparator.comparingInt(ImageVisionProvider::autoDetectOrder))
                .toList();
        if (available.isEmpty()) {
            throw new MateClawException("err.wiki.vision.no_provider",
                    "No image vision provider is configured");
        }

        VisionResult winner = null;
        Throwable lastError = null;
        for (ImageVisionProvider provider : available) {
            long startNanos = System.nanoTime();
            try {
                winner = provider.caption(request, settings);
                metrics.recordVisionCall(provider.id(), true,
                        Duration.ofNanos(System.nanoTime() - startNanos));
                if (winner != null) {
                    break;
                }
            } catch (Exception e) {
                metrics.recordVisionCall(provider.id(), false,
                        Duration.ofNanos(System.nanoTime() - startNanos));
                log.warn("[Vision] provider={} failed sha={}: {}",
                        provider.id(), shortSha(sha256), e.getMessage());
                lastError = e;
            }
        }

        if (winner == null) {
            String why = lastError != null ? lastError.getMessage() : "all providers returned null";
            throw new MateClawException("err.wiki.vision.all_failed",
                    "All image vision providers failed: " + why);
        }

        // 3. Persist to cache.
        cacheService.persist(toEntity(sha256, winner, request.getMimeType()));

        return winner;
    }

    // ==================== internal ====================

    private static VisionResult fromEntity(WikiImageCaptionCacheEntity row) {
        Instant captured = row.getCapturedAt() == null
                ? Instant.now()
                : row.getCapturedAt().atZone(ZoneId.systemDefault()).toInstant();
        return VisionResult.builder()
                .caption(row.getCaption())
                .visibleText(row.getVisibleText())
                .providerId(row.getProviderId())
                .model(row.getCaptureModel())
                .capturedAt(captured)
                .durationMs(row.getDurationMs() != null ? row.getDurationMs() : 0L)
                .build();
    }

    private static WikiImageCaptionCacheEntity toEntity(String sha256, VisionResult result,
                                                         String mimeType) {
        WikiImageCaptionCacheEntity row = new WikiImageCaptionCacheEntity();
        row.setImageSha256(sha256);
        row.setCaption(result.getCaption());
        row.setVisibleText(result.getVisibleText());
        row.setMimeType(mimeType);
        row.setCaptureModel(result.getModel());
        row.setProviderId(result.getProviderId());
        row.setDurationMs(result.getDurationMs());
        row.setHitCount(0L);
        row.setCapturedAt(result.getCapturedAt() != null
                ? LocalDateTime.ofInstant(result.getCapturedAt(), ZoneId.systemDefault())
                : LocalDateTime.now());
        return row;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    private static String shortSha(String sha) {
        return sha.substring(0, Math.min(8, sha.length()));
    }
}
