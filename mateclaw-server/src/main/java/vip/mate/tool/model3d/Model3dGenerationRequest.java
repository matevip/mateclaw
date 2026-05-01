package vip.mate.tool.model3d;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Unified 3D-model generation request. Mirrors the shape of
 * {@link vip.mate.tool.video.VideoGenerationRequest} and
 * {@link vip.mate.tool.image.ImageGenerationRequest}.
 */
@Data
@Builder
public class Model3dGenerationRequest {

    /** Text prompt describing the desired 3D model (object name, style, materials). */
    private String prompt;

    /** Generation mode. Inferred from inputs when null. */
    private Model3dCapability mode;

    /** Provider-specific model id (provider has a default). */
    private String model;

    /**
     * Single reference image URL for {@link Model3dCapability#IMAGE_TO_3D}.
     * Mutually exclusive with {@link #imageUrls}.
     */
    private String imageUrl;

    /**
     * Multi-view reference image URLs for {@link Model3dCapability#MULTI_VIEW_TO_3D}.
     * Typically 4 views (front / left / right / back).
     */
    private List<String> imageUrls;

    /** Output format: glb / obj / fbx. Provider may not support all. */
    @Builder.Default
    private String outputFormat = "glb";

    /** Whether to bake textures into the mesh. */
    @Builder.Default
    private Boolean enableTexture = true;

    /** Whether to generate physically-based rendering (PBR) materials. */
    @Builder.Default
    private Boolean enablePbr = false;

    /** Provider-specific extra parameters. */
    private Map<String, Object> extraParams;
}
