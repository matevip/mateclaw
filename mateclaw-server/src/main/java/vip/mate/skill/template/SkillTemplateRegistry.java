package vip.mate.skill.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-091 — registry for built-in skill creation templates.
 *
 * <p>Loads {@code skill-templates/&#42;&#47;template.json} from the
 * classpath at startup and exposes them via {@link #all()} /
 * {@link #find(String)}. We intentionally don't watch the filesystem —
 * additions land via redeploys, mirroring how SKILL.md skills work.
 *
 * <p>Templates with malformed JSON are logged and skipped rather than
 * failing startup, so a single bad template can't block the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillTemplateRegistry {

    private static final String TEMPLATE_PATTERN = "classpath:/skill-templates/*/template.json";

    private final ObjectMapper objectMapper;

    /**
     * Insertion order matches the alphabetical scan, which keeps the
     * gallery deterministic across deployments. Frontend can re-sort.
     */
    private final Map<String, SkillTemplate> templates = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        templates.clear();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(TEMPLATE_PATTERN);
            for (Resource r : resources) {
                try (InputStream in = r.getInputStream()) {
                    SkillTemplate tpl = objectMapper.readValue(in, SkillTemplate.class);
                    if (tpl.getId() == null || tpl.getId().isBlank()) {
                        log.warn("Skipping skill template at {} — missing id", r.getDescription());
                        continue;
                    }
                    templates.put(tpl.getId(), tpl);
                    log.info("Loaded skill template: {} ({})", tpl.getId(), tpl.getType());
                } catch (Exception e) {
                    log.warn("Failed to parse skill template at {}: {}",
                            r.getDescription(), e.getMessage());
                }
            }
            log.info("SkillTemplateRegistry loaded {} template(s)", templates.size());
        } catch (Exception e) {
            log.warn("Skill template scan failed; gallery will be empty: {}", e.getMessage());
        }
    }

    /** All registered templates, deterministic order. */
    public List<SkillTemplate> all() {
        return new ArrayList<>(templates.values());
    }

    /** Lookup by id; null when missing. */
    public SkillTemplate find(String id) {
        return templates.get(id);
    }
}
