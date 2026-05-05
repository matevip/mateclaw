package vip.mate.tool.image;

/**
 * 图片相关能力枚举（生成方向 + 解析方向）。
 *
 * @author MateClaw Team
 */
public enum ImageCapability {

    /** 文字生成图片 */
    TEXT_TO_IMAGE,

    /** 图片编辑 / 风格转换 */
    IMAGE_EDIT,

    /** Image to text — vision-in pipeline (factual caption + best-effort visible text). */
    IMAGE_TO_TEXT
}
