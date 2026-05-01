package vip.mate.skill.template;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.service.SkillService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-091 — instantiate a {@link SkillTemplate} into a real
 * {@code mate_skill} row.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Validate every required field has a non-blank value.</li>
 *   <li>Compute auxiliary placeholders (e.g. {@code citation_string}
 *       from a boolean toggle) so the SKILL.md doesn't need conditional
 *       logic.</li>
 *   <li>Substitute {@code {{key}}} occurrences in the template body.</li>
 *   <li>Build a {@link SkillEntity} and hand it to
 *       {@link SkillService#createSkill}, which persists the row,
 *       initializes the workspace directory, and refreshes the runtime
 *       cache. The resolver will then pick up the manifest.</li>
 * </ol>
 *
 * <p>This intentionally goes through {@code SkillService.createSkill}
 * rather than the install task pipeline — the wizard produces a local
 * skill from in-process content, no bundle download involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final SkillTemplateRegistry registry;
    private final SkillService skillService;

    /**
     * Instantiate the template by id, substituting fields, and create
     * the skill. Returns the created {@link SkillEntity}.
     *
     * @param templateId id from the registry (e.g. {@code tcm-qa})
     * @param values     user-supplied field values; missing required
     *                   fields throw a translatable exception
     */
    public SkillEntity instantiate(String templateId, Map<String, Object> values) {
        SkillTemplate template = registry.find(templateId);
        if (template == null) {
            throw new MateClawException("err.skill_template.not_found",
                    "Skill template not found: " + templateId);
        }
        if (values == null) values = Map.of();

        // 1. validate + collect into a single substitution map
        Map<String, String> substitutions = collectSubstitutions(template, values);

        // 2. render SKILL.md
        String skillMd = render(template.getSkillMd(), substitutions);

        // 3. build entity and create via SkillService (which already
        //    handles uniqueness, defaults, workspace init, runtime
        //    cache refresh).
        SkillEntity entity = new SkillEntity();
        entity.setName(substitutions.get("skill_name"));
        String displayZh = substitutions.getOrDefault("display_name_zh", "");
        String displayEn = substitutions.getOrDefault("display_name_en",
                substitutions.getOrDefault("display_name", ""));
        entity.setNameZh(displayZh.isBlank() ? null : displayZh);
        entity.setNameEn(displayEn.isBlank() ? null : displayEn);
        entity.setDescription(template.getDescription());
        entity.setSkillType(mapType(template.getType()));
        entity.setIcon(template.getIcon());
        entity.setVersion("1.0.0");
        entity.setAuthor("skill-template-wizard");
        entity.setSkillContent(skillMd);
        entity.setEnabled(true);

        return skillService.createSkill(entity);
    }

    private String mapType(String type) {
        if (type == null) return "dynamic";
        // RFC-090 §5.1 introduced more types, but mate_skill.skill_type
        // historically only knows builtin / mcp / dynamic. Map knowledge /
        // prompt back to dynamic for legacy callers; the manifest_json
        // column carries the real v3 type.
        return switch (type) {
            case "mcp" -> "mcp";
            case "builtin" -> "builtin";
            default -> "dynamic";
        };
    }

    private Map<String, String> collectSubstitutions(SkillTemplate template,
                                                      Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SkillTemplate.TemplateField field : template.getFields()) {
            Object raw = values.get(field.getKey());
            String resolved = raw == null ? null : raw.toString().trim();
            if ((resolved == null || resolved.isBlank())) {
                if (field.getDefaultValue() != null) {
                    resolved = field.getDefaultValue().toString();
                } else if (field.isRequired()) {
                    throw new MateClawException("err.skill_template.missing_field",
                            "Required field missing: " + field.getKey());
                } else {
                    resolved = "";
                }
            }
            out.put(field.getKey(), resolved);
        }
        // Auxiliary derived placeholders so SKILL.md templates stay simple.
        if (out.containsKey("citation_required")) {
            boolean req = Boolean.parseBoolean(out.get("citation_required"));
            out.put("citation_string", req ? "required" : "optional");
            out.put("citation_instruction", req
                    ? "**每条建议必须标明引用的 KB 出处** ({{citation}} 自动注入)。"
                    : "如有 KB 引用，按 {{citation}} 标注；否则可省略。");
        }
        if (out.containsKey("output_language")) {
            String lang = out.get("output_language");
            out.put("output_language_label", "zh".equalsIgnoreCase(lang) ? "中文" : "English");
        }
        return out;
    }

    private String render(String template, Map<String, String> values) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
