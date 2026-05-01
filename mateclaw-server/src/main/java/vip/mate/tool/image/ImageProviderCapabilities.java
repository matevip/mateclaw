package vip.mate.tool.image;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * 图片生成 Provider 细粒度能力声明
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class ImageProviderCapabilities {

    /** 支持的生成模式 */
    @Builder.Default
    private Set<ImageCapability> modes = Set.of(ImageCapability.TEXT_TO_IMAGE);

    /** 支持的图片尺寸，如 ["1024x1024", "1024x1792"] */
    @Builder.Default
    private List<String> supportedSizes = List.of("1024x1024");

    /** 支持的画面比例 */
    @Builder.Default
    private List<String> aspectRatios = List.of("1:1", "16:9", "9:16");

    /** 最大生成数量 */
    @Builder.Default
    private int maxCount = 1;

    /** 默认模型 */
    private String defaultModel;

    /** 可用模型列表 */
    @Builder.Default
    private List<String> models = List.of();

    /**
     * Match the requested size against supported sizes by area only.
     * Orientation-blind — prefer {@link #normalizeSize(String, String)} when an
     * aspect ratio is available so portrait/landscape intent is preserved.
     */
    public String normalizeSize(String requested) {
        return normalizeSize(requested, null);
    }

    /**
     * Match the requested size against supported sizes, preserving orientation.
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code requestedSize} is already in {@code supportedSizes}, return it.</li>
     *   <li>If {@code requestedAspectRatio} is given, narrow {@code supportedSizes}
     *       to those whose orientation matches (portrait / landscape / square),
     *       then pick by closest area.</li>
     *   <li>Otherwise pick by closest area across all supported sizes.</li>
     * </ol>
     */
    public String normalizeSize(String requestedSize, String requestedAspectRatio) {
        if (supportedSizes.isEmpty()) {
            return "1024x1024";
        }
        if (requestedSize != null && supportedSizes.contains(requestedSize)) {
            return requestedSize;
        }

        Orientation targetOrientation = orientationFor(requestedAspectRatio);
        List<String> candidates = supportedSizes;
        if (targetOrientation != null) {
            List<String> matching = supportedSizes.stream()
                    .filter(s -> orientationOf(s) == targetOrientation)
                    .toList();
            if (!matching.isEmpty()) {
                candidates = matching;
            }
        }

        long reqArea = (requestedSize == null || requestedSize.isBlank())
                ? 1024L * 1024L
                : parseArea(requestedSize);
        String closest = candidates.get(0);
        long minDiff = Math.abs(reqArea - parseArea(closest));
        for (String s : candidates) {
            long diff = Math.abs(reqArea - parseArea(s));
            if (diff < minDiff) {
                minDiff = diff;
                closest = s;
            }
        }
        return closest;
    }

    private enum Orientation { PORTRAIT, LANDSCAPE, SQUARE }

    private static Orientation orientationFor(String aspectRatio) {
        if (aspectRatio == null || aspectRatio.isBlank()) return null;
        String[] parts = aspectRatio.split(":");
        if (parts.length != 2) return null;
        try {
            double w = Double.parseDouble(parts[0].trim());
            double h = Double.parseDouble(parts[1].trim());
            if (w == h) return Orientation.SQUARE;
            return w > h ? Orientation.LANDSCAPE : Orientation.PORTRAIT;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Orientation orientationOf(String size) {
        try {
            String[] parts = size.toLowerCase().split("x");
            long w = Long.parseLong(parts[0].trim());
            long h = Long.parseLong(parts[1].trim());
            if (w == h) return Orientation.SQUARE;
            return w > h ? Orientation.LANDSCAPE : Orientation.PORTRAIT;
        } catch (Exception e) {
            return Orientation.SQUARE;
        }
    }

    /**
     * 将请求的 aspectRatio 就近匹配或回退到默认
     */
    public String normalizeAspectRatio(String requested) {
        if (aspectRatios.contains(requested)) {
            return requested;
        }
        return aspectRatios.isEmpty() ? "1:1" : aspectRatios.get(0);
    }

    /**
     * 将请求的 count 限制在 provider 支持范围内
     */
    public int normalizeCount(int requested) {
        return Math.min(Math.max(requested, 1), maxCount);
    }

    private long parseArea(String size) {
        try {
            String[] parts = size.toLowerCase().split("x");
            return Long.parseLong(parts[0].trim()) * Long.parseLong(parts[1].trim());
        } catch (Exception e) {
            return 1024L * 1024L;
        }
    }
}
