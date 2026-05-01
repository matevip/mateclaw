package vip.mate.skill.knowledge;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.wiki.dto.PageSearchResult;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RFC-090 §14.4 — generates skill-scoped wrapper {@link ToolCallback}s
 * for {@code type: knowledge} skills.
 *
 * <p>For each knowledge skill we register exactly three tools:
 * <ul>
 *   <li>{@code kb_<slug>_search} → semantic / keyword / hybrid search</li>
 *   <li>{@code kb_<slug>_read}   → fetch a page by slug</li>
 *   <li>{@code kb_<slug>_list}   → enumerate pages, optionally filtered</li>
 * </ul>
 *
 * <p>Each callback closes over the resolved {@code kbId} so the LLM
 * never has to (and can't accidentally) target a different KB. Multiple
 * knowledge skills bound to the same agent each get their own tool
 * surface — no ThreadLocal, no ToolContext sneak-through (per §14.4
 * v3.3 校准).
 *
 * <p>Output JSON shapes mirror {@code WikiTool}'s @Tool methods so
 * downstream prompt logic stays compatible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikiSkillWrapperToolFactory {

    private final WikiKnowledgeBaseService kbService;
    private final WikiPageService pageService;
    private final HybridRetriever hybridRetriever;
    private final ObjectMapper objectMapper;

    /**
     * Resolve the manifest's {@code knowledge.bind_kb} (a slug or
     * numeric id, the spec is loose because KBs don't yet have slugs)
     * to a concrete kbId. Returns null when no matching KB exists —
     * the caller decides whether that's a fatal install-time error or
     * a deferred hint.
     */
    public Long resolveKbId(String bindKb) {
        if (bindKb == null || bindKb.isBlank()) return null;
        String trimmed = bindKb.trim();
        // Numeric id form first (KB picker writes id when slug is absent).
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            /* fall through to name lookup */
        }
        // Name match (case-insensitive).
        return kbService.listAll().stream()
                .filter(kb -> kb.getName() != null && kb.getName().equalsIgnoreCase(trimmed))
                .map(WikiKnowledgeBaseEntity::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Build the 3 wrapper callbacks for one knowledge skill. Returns an
     * empty list when {@code kbId} cannot be resolved — the caller logs
     * + skips registration so the skill ends up READY=false rather than
     * advertising broken tools.
     */
    public List<ToolCallback> buildWrappers(SkillManifest manifest, Long resolvedKbId) {
        List<ToolCallback> out = new ArrayList<>(3);
        if (manifest == null || manifest.getKnowledge() == null || resolvedKbId == null) {
            return out;
        }
        String namePart = sanitize(manifest.getName());
        if (namePart.isBlank()) return out;
        String prefix = "kb_" + namePart;

        out.add(buildSearchTool(prefix, resolvedKbId, manifest));
        out.add(buildReadTool(prefix, resolvedKbId, manifest));
        out.add(buildListTool(prefix, resolvedKbId, manifest));
        return out;
    }

    /**
     * Names of the wrappers a manifest would produce, without actually
     * building them. Used by {@link vip.mate.skill.runtime.SkillPackageResolver}
     * to populate {@code manifest.allowedTools} so {@code
     * ResolvedSkill.getEffectiveAllowedTools} surfaces the right names
     * even before the registration side-effect runs.
     */
    public List<String> wrapperNames(SkillManifest manifest) {
        if (manifest == null || manifest.getName() == null || manifest.getName().isBlank()) {
            return List.of();
        }
        String prefix = "kb_" + sanitize(manifest.getName());
        return List.of(prefix + "_search", prefix + "_read", prefix + "_list");
    }

    /**
     * Tool name slug rule: lowercase, replace any non-[a-z0-9_] with '_'.
     * Names that come out of this are stable across resolves so the
     * registry diff stays clean.
     */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    // ==================== search ====================

    private ToolCallback buildSearchTool(String prefix, long kbId, SkillManifest manifest) {
        String name = prefix + "_search";
        String desc = String.format(
                "Search the '%s' knowledge base. Returns up to topK pages with snippet and slug. "
                + "Use the slug with %s_read to fetch full content. Always cite page title in answers.",
                manifest.getName(), prefix);
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"search query\"},"
                + "\"mode\":{\"type\":\"string\",\"enum\":[\"keyword\",\"semantic\",\"hybrid\"],\"description\":\"retrieval mode (default: hybrid)\"},"
                + "\"topK\":{\"type\":\"integer\",\"description\":\"max results, default 5, max 20\"}"
                + "},"
                + "\"required\":[\"query\"]"
                + "}";
        return new SkillScopedToolCallback(name, desc, schema, input -> doSearch(kbId, input));
    }

    private String doSearch(long kbId, String input) {
        try {
            JsonNode args = parseArgs(input);
            String query = textOrEmpty(args, "query");
            if (query.isEmpty()) return errorJson("query is required");
            String mode = textOrEmpty(args, "mode");
            int topK = args.path("topK").isNumber()
                    ? Math.min(args.path("topK").asInt(5), 20) : 5;

            List<PageSearchResult> results = hybridRetriever.search(kbId, query,
                    mode.isEmpty() ? null : mode, topK);
            for (PageSearchResult r : results) pageService.trackReference(kbId, r.slug());

            JSONArray arr = new JSONArray();
            for (PageSearchResult r : results) {
                arr.add(JSONUtil.createObj()
                        .set("slug", r.slug())
                        .set("title", r.title())
                        .set("snippet", r.snippet())
                        .set("matchedBy", r.matchedBy())
                        .set("score", r.score()));
            }
            return JSONUtil.createObj()
                    .set("kbId", kbId)
                    .set("count", results.size())
                    .set("results", arr)
                    .toString();
        } catch (Exception e) {
            log.warn("kb search wrapper failed: {}", e.getMessage());
            return errorJson("search failed: " + e.getMessage());
        }
    }

    // ==================== read ====================

    private ToolCallback buildReadTool(String prefix, long kbId, SkillManifest manifest) {
        String name = prefix + "_read";
        String desc = String.format(
                "Read a page from the '%s' knowledge base by slug. Use maxChars to limit response size; "
                + "use sectionHeading to extract a single section.",
                manifest.getName());
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"slug\":{\"type\":\"string\",\"description\":\"page slug\"},"
                + "\"maxChars\":{\"type\":\"integer\",\"description\":\"truncate to this many chars\"},"
                + "\"sectionHeading\":{\"type\":\"string\",\"description\":\"only return the section under this heading\"}"
                + "},"
                + "\"required\":[\"slug\"]"
                + "}";
        return new SkillScopedToolCallback(name, desc, schema, input -> doRead(kbId, input));
    }

    private String doRead(long kbId, String input) {
        try {
            JsonNode args = parseArgs(input);
            String slug = textOrEmpty(args, "slug");
            if (slug.isEmpty()) return errorJson("slug is required");
            WikiPageEntity page = pageService.getBySlug(kbId, slug);
            if (page == null) return errorJson("page not found: " + slug);
            pageService.trackReference(kbId, slug);

            String content = page.getContent() == null ? "" : page.getContent();
            String section = textOrEmpty(args, "sectionHeading");
            if (!section.isEmpty()) content = extractSection(content, section);
            int maxChars = args.path("maxChars").isNumber() ? args.path("maxChars").asInt(0) : 0;
            if (maxChars > 0 && content.length() > maxChars) {
                content = content.substring(0, maxChars) + "\n…(truncated)";
            }
            JSONObject result = JSONUtil.createObj()
                    .set("title", page.getTitle())
                    .set("slug", page.getSlug())
                    .set("version", page.getVersion())
                    .set("content", content);
            return result.toString();
        } catch (Exception e) {
            log.warn("kb read wrapper failed: {}", e.getMessage());
            return errorJson("read failed: " + e.getMessage());
        }
    }

    // ==================== list ====================

    private ToolCallback buildListTool(String prefix, long kbId, SkillManifest manifest) {
        String name = prefix + "_list";
        String desc = String.format(
                "List pages in the '%s' knowledge base. Pass an optional 'query' to filter by title keyword.",
                manifest.getName());
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"description\":\"optional title keyword filter\"}"
                + "}"
                + "}";
        return new SkillScopedToolCallback(name, desc, schema, input -> doList(kbId, input));
    }

    private String doList(long kbId, String input) {
        try {
            JsonNode args = parseArgs(input);
            String query = textOrEmpty(args, "query");

            List<WikiPageEntity> pages;
            if (!query.isEmpty()) {
                pages = pageService.searchPages(kbId, query).stream()
                        .filter(p -> !"system".equals(p.getPageType()))
                        .limit(30).toList();
            } else {
                pages = pageService.listSummaries(kbId).stream()
                        .filter(p -> !"system".equals(p.getPageType()))
                        .toList();
            }

            JSONArray arr = new JSONArray();
            for (WikiPageEntity p : pages) {
                arr.add(JSONUtil.createObj()
                        .set("slug", p.getSlug())
                        .set("title", p.getTitle())
                        .set("summary", p.getSummary()));
            }
            return JSONUtil.createObj()
                    .set("kbId", kbId)
                    .set("pageCount", pages.size())
                    .set("pages", arr)
                    .toString();
        } catch (Exception e) {
            log.warn("kb list wrapper failed: {}", e.getMessage());
            return errorJson("list failed: " + e.getMessage());
        }
    }

    // ==================== helpers ====================

    private JsonNode parseArgs(String input) throws Exception {
        if (input == null || input.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(input);
    }

    private static String textOrEmpty(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return "";
        return v.asText("").trim();
    }

    private static String errorJson(String msg) {
        return JSONUtil.createObj().set("error", msg).toString();
    }

    /**
     * Same heading-extract shape WikiTool uses — keeps wrapper output
     * indistinguishable from native @Tool output for skills that
     * already documented their KB workflows.
     */
    private static String extractSection(String content, String heading) {
        if (content == null || content.isEmpty()) return "";
        String[] lines = content.split("\n");
        StringBuilder out = new StringBuilder();
        boolean capturing = false;
        int captureLevel = 0;
        for (String line : lines) {
            String stripped = line.stripLeading();
            int level = 0;
            while (level < stripped.length() && stripped.charAt(level) == '#') level++;
            boolean isHeading = level > 0 && level < stripped.length() && stripped.charAt(level) == ' ';
            if (isHeading) {
                String headingText = stripped.substring(level + 1).trim();
                if (!capturing) {
                    if (headingText.equalsIgnoreCase(heading.trim())) {
                        capturing = true;
                        captureLevel = level;
                        out.append(line).append('\n');
                    }
                    continue;
                } else {
                    if (level <= captureLevel) {
                        break;
                    }
                }
            }
            if (capturing) out.append(line).append('\n');
        }
        return out.toString();
    }
}
