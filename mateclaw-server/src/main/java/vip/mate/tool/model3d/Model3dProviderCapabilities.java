package vip.mate.tool.model3d;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Per-provider 3D-model capability declaration. Each provider declares its
 * supported modes, output formats, and model catalog so the runtime can
 * normalize a request to what the provider accepts.
 */
@Data
@Builder
public class Model3dProviderCapabilities {

    /** Modes the provider can serve. */
    @Builder.Default
    private Set<Model3dCapability> modes = Set.of(Model3dCapability.TEXT_TO_3D);

    /** Output formats the provider can return: glb / obj / fbx / usdz. */
    @Builder.Default
    private List<String> supportedFormats = List.of("glb");

    /** Whether the provider supports texture baking. */
    @Builder.Default
    private boolean supportsTexture = true;

    /** Whether the provider supports PBR materials. */
    @Builder.Default
    private boolean supportsPbr = false;

    /** Default model id. */
    private String defaultModel;

    /** Available model ids on this provider. */
    @Builder.Default
    private List<String> models = List.of();

    /**
     * Pick a supported output format closest to the request, falling back to
     * the first supported one.
     */
    public String normalizeFormat(String requested) {
        if (requested == null || requested.isBlank()) {
            return supportedFormats.isEmpty() ? "glb" : supportedFormats.get(0);
        }
        String lower = requested.toLowerCase();
        for (String f : supportedFormats) {
            if (f.equalsIgnoreCase(lower)) return f;
        }
        return supportedFormats.isEmpty() ? "glb" : supportedFormats.get(0);
    }
}
