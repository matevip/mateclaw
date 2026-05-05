package vip.mate.skill.workspace.bundle;

/**
 * Behavior knobs for {@link SkillBundleMaterializer#materialize}.
 *
 * <p>Predefined factories cover the two known call sites; new modes go
 * here rather than as ad-hoc boolean parameters at the call site.
 */
public record MaterializeOptions(boolean skipSkillMd) {

    /**
     * Copy every asset verbatim, including {@code SKILL.md}. Used by the
     * RFC-044 builtin sync path where the classpath SKILL.md is the
     * source of truth.
     */
    public static MaterializeOptions verbatim() {
        return new MaterializeOptions(false);
    }

    /**
     * Skip top-level {@code SKILL.md} so a caller-rendered manifest stays
     * authoritative. Used by the RFC-091 template wizard, where
     * {@code template.json#skillMd} is rendered with placeholders before
     * being written to the workspace.
     */
    public static MaterializeOptions templateOverlay() {
        return new MaterializeOptions(true);
    }
}
