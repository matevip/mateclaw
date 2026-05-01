package vip.mate.skill.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RFC-090 Phase 2 — parsed SKILL.md manifest (source of truth, §14.6).
 *
 * <p>Maps the YAML frontmatter shape from §5.1 onto a typed model.
 * Persisted to {@code mate_skill.manifest_json}; legacy columns
 * (skill_type / icon / version / author) are projected from this
 * after each resolve.
 *
 * <p>All collection fields default to empty so consumers don't need
 * null guards. Unknown YAML keys are preserved in
 * {@link #extras} for forward compatibility.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class SkillManifest {

    // ==================== Identity ====================

    private String id;
    private String name;
    private String description;
    private String icon;
    private String version;
    private String author;

    /** prompt | code | mcp | acp | knowledge */
    private String type;

    /** file | web | data | content | comm | system | ... (free-form tag) */
    private String category;

    // ==================== Tools / dependencies ====================

    /** Anthropic-compatible {@code allowed-tools} list. */
    @Builder.Default
    private List<String> allowedTools = List.of();

    /** Top-level {@code requires} entries. */
    @Builder.Default
    private List<RequirementDef> requires = List.of();

    /** Top-level {@code platforms} list (overall package compatibility). */
    @Builder.Default
    private List<String> platforms = List.of();

    /**
     * v3.1 {@code features[]} matrix. Empty list means
     * "no explicit feature partitioning" — the resolver synthesizes a
     * single default feature carrying the top-level {@link #requires}
     * and {@link #platforms} so legacy skills behave unchanged.
     */
    @Builder.Default
    private List<FeatureDef> features = List.of();

    // ==================== User-facing settings ====================

    @Builder.Default
    private List<SettingDef> settings = List.of();

    // ==================== Provider routing ====================

    @Builder.Default
    private List<String> requiresModel = List.of();

    // ==================== Dashboard ====================

    @Builder.Default
    private List<DashboardMetric> dashboardMetrics = List.of();

    // ==================== v3 self-evolution ====================

    @Builder.Default
    private SelfEvolution selfEvolution = SelfEvolution.defaults();

    // ==================== v3.1 knowledge type ====================

    private KnowledgeBinding knowledge;

    // ==================== Forward-compat catch-all ====================

    /** Unknown frontmatter keys are stashed here so a future field
     *  doesn't drop on parse — the JSON round-trips intact. */
    @Builder.Default
    private Map<String, Object> extras = Map.of();

    // ==================== Nested types ====================

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class RequirementDef {
        /** Required: stable identifier referenced by {@code features[*].requires}. */
        private String key;
        /** binary | env_var | api_key */
        private String type;
        /** Probe target — for binary, the executable name; for env_var, the env name. */
        private String check;
        /** Optional means it only blocks features that reference it explicitly. */
        @Builder.Default
        private boolean optional = false;
        private String description;
        @Builder.Default
        private Map<String, String> install = Map.of();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class FeatureDef {
        private String id;
        private String label;
        @Builder.Default
        private List<String> requires = List.of();
        @Builder.Default
        private List<String> platforms = List.of();
        /** Tools advertised only when this feature is READY. Empty
         *  means "inherit the manifest-level allowed-tools as-is". */
        @Builder.Default
        private List<String> tools = List.of();
        private String fallbackMessage;
        private String unsupportedMessage;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SettingDef {
        private String key;
        private String label;
        /** select | text | secret | toggle */
        private String type;
        private Object defaultValue;
        @Builder.Default
        private List<Map<String, Object>> options = List.of();
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class DashboardMetric {
        private String label;
        private String memoryKey;
        private String format;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class SelfEvolution {
        @Builder.Default
        private boolean lessonsEnabled = true;
        @Builder.Default
        private int lessonsMaxEntries = 50;
        @Builder.Default
        private boolean memoryWritesAllowed = true;

        public static SelfEvolution defaults() {
            return SelfEvolution.builder().build();
        }
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class KnowledgeBinding {
        /** KB slug declared in manifest (e.g. {@code tcm-classics}). */
        private String bindKb;
        /** vector | bm25 | hybrid */
        private String retrieval;
        @Builder.Default
        private int topK = 6;
        /** required | optional | none */
        @Builder.Default
        private String citation = "optional";
        @Builder.Default
        private boolean rerank = false;

        /** Resolved KB id, written at install time after slug lookup
         *  (RFC-090 §14.4). Null until resolved. */
        private Long boundKbId;
    }
}
