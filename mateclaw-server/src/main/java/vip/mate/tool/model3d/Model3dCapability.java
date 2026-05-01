package vip.mate.tool.model3d;

/**
 * 3D model generation capability modes.
 */
public enum Model3dCapability {

    /** Pure text-to-3D — generate a model from a textual prompt only. */
    TEXT_TO_3D,

    /** Image-to-3D — single reference image plus optional text. */
    IMAGE_TO_3D,

    /** Multi-view to 3D — multiple reference images for higher fidelity. */
    MULTI_VIEW_TO_3D
}
