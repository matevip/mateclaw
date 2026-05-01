package vip.mate.skill.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * RFC-091 — definition of a skill creation template.
 *
 * <p>Templates are loaded from
 * {@code resources/skill-templates/{id}/template.json} at startup. The
 * frontend wizard renders the {@link #fields} as a form, the user fills
 * them in, and the resulting key/value map is substituted into the
 * {@link #skillMd} jinja-style placeholders to produce a manifest skill.
 *
 * <p>This is the data model only — see {@link SkillTemplateRegistry} for
 * loading and {@link SkillTemplateService#instantiate} for the actual
 * substitution + install path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SkillTemplate {

    /** Unique slug, matches the resource directory name. */
    private String id;

    private String name;
    private String nameZh;
    private String nameEn;
    private String description;
    private String descriptionZh;

    /** prompt | knowledge | code | mcp | acp — drives wizard branching. */
    private String type;

    private String category;
    private String icon;

    /** Form fields the wizard renders. */
    @Builder.Default
    private List<TemplateField> fields = List.of();

    /**
     * Skill body with {@code {{placeholders}}} ready for substitution.
     * Stored verbatim from {@code template.json}; line endings preserved.
     */
    private String skillMd;

    /**
     * Optional classpath-relative directory whose files should be copied
     * into the new skill's workspace alongside the rendered SKILL.md —
     * e.g. {@code skill-template-bundles/airtable}. Lets a template ship
     * scripts/, references/, and assets that are too large or too binary
     * to inline in {@link #skillMd}.
     *
     * <p>The top-level {@code SKILL.md} in the bundle (if any) is skipped
     * automatically so the wizard's rendered manifest stays authoritative;
     * see {@code MaterializeOptions.templateOverlay()}.
     */
    private String bundlePath;

    /** Forward-compat catch-all for fields the wizard hasn't typed yet. */
    @Builder.Default
    private Map<String, Object> extras = Map.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class TemplateField {
        private String key;
        private String label;
        /** text | textarea | select | toggle | kb-picker */
        private String type;
        @Builder.Default
        private boolean required = false;
        private String placeholder;
        private String hint;
        /** JSON {@code "default"} maps here — {@code default} is a Java keyword. */
        @JsonProperty("default")
        private Object defaultValue;
        @Builder.Default
        private List<Map<String, Object>> options = List.of();
    }
}
