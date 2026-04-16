package vip.mate.wiki.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.List;

/**
 * Wiki 知识库工具
 * <p>
 * 供 Agent 在对话中按需读取 Wiki 页面内容。
 * kbId 通过 agentId 自动解析，LLM 无需传递。
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikiTool {

    private final WikiPageService pageService;
    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final HybridRetriever hybridRetriever;

    @Tool(description = """
            读取 Wiki 知识库中指定页面的完整内容。
            当系统提示词中的 Wiki 页面摘要不够详细时，使用此工具获取完整内容。
            返回 Markdown 格式的页面内容，包含 [[双向链接]] 和来源原始文件信息。
            """)
    public String wiki_read_page(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "页面标识符 (slug)") String slug) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        // Agent 引用追踪
        pageService.trackReference(kbId, slug);

        JSONObject result = JSONUtil.createObj()
                .set("title", page.getTitle())
                .set("slug", page.getSlug())
                .set("version", page.getVersion())
                .set("lastUpdatedBy", page.getLastUpdatedBy())
                .set("content", page.getContent())
                .set("sourceFiles", resolveSourceFiles(page.getSourceRawIds()));
        return result.toString();
    }

    @Tool(description = """
            列出 Wiki 知识库中的所有页面。
            返回页面列表，包含标题、slug 和摘要。
            """)
    public String wiki_list_pages(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId) {

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        List<WikiPageEntity> pages = pageService.listSummaries(kbId);
        JSONArray arr = new JSONArray();
        for (WikiPageEntity page : pages) {
            arr.add(JSONUtil.createObj()
                    .set("title", page.getTitle())
                    .set("slug", page.getSlug())
                    .set("summary", page.getSummary()));
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("pageCount", pages.size())
                .set("pages", arr)
                .toString();
    }

    @Tool(description = """
            在 Wiki 知识库中搜索页面。
            支持三种模式：keyword（关键词匹配）、semantic（语义向量相似度）、hybrid（两者融合，默认）。
            返回匹配的页面列表及其来源文件。
            """)
    public String wiki_search_pages(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "搜索关键词或自然语言问题") String query,
            @ToolParam(description = "搜索模式：keyword | semantic | hybrid（默认 hybrid）", required = false) String mode) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        // RFC-011：走混合检索
        List<HybridRetriever.PageHit> hits = hybridRetriever.searchPages(kbId, query, mode, 20);

        // Agent 引用追踪
        for (HybridRetriever.PageHit h : hits) {
            pageService.trackReference(kbId, h.slug());
        }

        JSONArray arr = new JSONArray();
        for (HybridRetriever.PageHit hit : hits) {
            arr.add(JSONUtil.createObj()
                    .set("title", hit.title())
                    .set("slug", hit.slug())
                    .set("summary", hit.summary())
                    .set("score", String.format("%.4f", hit.score())));
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("query", query)
                .set("mode", mode != null ? mode : "hybrid")
                .set("matchCount", hits.size())
                .set("pages", arr)
                .toString();
    }

    @Tool(description = """
            在 Wiki 知识库中进行 chunk 级语义搜索。
            返回与查询语义最接近的原始文本片段（chunk），包含相似度分数。
            当 wiki_search_pages 返回的页面摘要不够具体时，使用此工具获取精确的源文本证据。
            """)
    public String wiki_semantic_search(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "自然语言查询") String query,
            @ToolParam(description = "返回条数（默认 5）", required = false) Integer topK) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        int k = (topK != null && topK > 0) ? Math.min(topK, 20) : 5;
        List<HybridRetriever.ChunkHit> hits = hybridRetriever.searchChunks(kbId, query, k);

        if (hits.isEmpty()) {
            return JSONUtil.createObj()
                    .set("kbId", kbId)
                    .set("query", query)
                    .set("matchCount", 0)
                    .set("message", "No semantic matches found. Try wiki_search_pages with mode=keyword.")
                    .toString();
        }

        JSONArray arr = new JSONArray();
        for (HybridRetriever.ChunkHit hit : hits) {
            // 解析 raw material 标题
            WikiRawMaterialEntity raw = rawService.getById(hit.rawId());
            arr.add(JSONUtil.createObj()
                    .set("chunkId", hit.chunkId())
                    .set("rawTitle", raw != null ? raw.getTitle() : "unknown")
                    .set("snippet", hit.snippet())
                    .set("score", String.format("%.4f", hit.score())));
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("query", query)
                .set("matchCount", hits.size())
                .set("chunks", arr)
                .toString();
    }

    @Tool(description = """
            追溯 Wiki 页面的来源原始文件。
            查询指定页面是由哪些原始文档生成的，返回文件名、类型、路径等信息。
            用于回答"这个内容出自哪篇文档"类的问题。
            """)
    public String wiki_trace_source(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "页面标识符 (slug)") String slug) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        return JSONUtil.createObj()
                .set("pageTitle", page.getTitle())
                .set("pageSlug", page.getSlug())
                .set("sourceFiles", resolveSourceFiles(page.getSourceRawIds()))
                .toString();
    }

    @Tool(description = """
            在 Wiki 知识库中创建新页面。
            用于保存任务执行结果、分析报告、会议纪要等有价值的信息。
            内容使用 Markdown 格式。页面标识符 (slug) 会从标题自动生成。
            """)
    public String wiki_create_page(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "页面标题") String title,
            @ToolParam(description = "页面内容 (Markdown 格式)") String content) {

        if (title == null || title.isBlank()) {
            return error("title is required");
        }
        if (content == null || content.isBlank()) {
            return error("content is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent. Create one first.");
        }

        // 从标题生成 slug
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isBlank()) {
            slug = "page-" + System.currentTimeMillis();
        }

        // 检查 slug 是否已存在
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing != null) {
            slug = slug + "-" + System.currentTimeMillis() % 10000;
        }

        // 生成摘要（取前 200 字符）
        String summary = content.length() > 200 ? content.substring(0, 200) + "..." : content;

        WikiPageEntity page = pageService.createPage(kbId, slug, title, content, summary, null);

        log.info("[WikiTool] Created page: {} (slug={}, kbId={})", title, slug, kbId);

        return JSONUtil.createObj()
                .set("ok", true)
                .set("message", "Page created successfully")
                .set("title", page.getTitle())
                .set("slug", page.getSlug())
                .set("kbId", kbId)
                .toString();
    }

    @Tool(description = """
            删除一个 AI 生成的 Wiki 页面。无法删除人工维护的页面（lastUpdatedBy = 'manual'）。
            用于清理过时、冗余或不准确的 Wiki 页面。
            """)
    public String wiki_delete_page(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "要删除的页面 slug") String slug) {

        if (slug == null || slug.isBlank()) {
            return error("slug is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) {
            return error("Page not found: " + slug);
        }

        // 安全保护：禁止删除人工维护的页面
        if ("manual".equals(page.getLastUpdatedBy())) {
            return error("Cannot delete manually curated page: " + page.getTitle() + ". Please manage via admin UI.");
        }

        pageService.delete(kbId, slug);
        log.info("[WikiTool] Deleted page: {} (slug={}, kbId={})", page.getTitle(), slug, kbId);

        return JSONUtil.createObj()
                .set("ok", true)
                .set("message", "Page deleted")
                .set("slug", slug)
                .set("title", page.getTitle())
                .toString();
    }

    /**
     * 通过 agentId 自动解析关联的知识库 ID
     * <p>
     * 查找逻辑：Agent 专属 KB + 公共 KB（agent_id IS NULL），取第一个。
     */
    private Long resolveKbId(Long agentId) {
        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        return kbs.isEmpty() ? null : kbs.get(0).getId();
    }

    /**
     * 将 sourceRawIds JSON 数组解析为原始文件信息列表
     */
    private JSONArray resolveSourceFiles(String sourceRawIdsJson) {
        JSONArray result = new JSONArray();
        if (sourceRawIdsJson == null || sourceRawIdsJson.isBlank()) return result;
        try {
            List<Long> rawIds = new ObjectMapper().readValue(sourceRawIdsJson, new TypeReference<List<Long>>() {});
            for (Long rawId : rawIds) {
                WikiRawMaterialEntity raw = rawService.getById(rawId);
                if (raw != null) {
                    result.add(JSONUtil.createObj()
                            .set("rawId", raw.getId())
                            .set("title", raw.getTitle())
                            .set("sourceType", raw.getSourceType())
                            .set("sourcePath", raw.getSourcePath()));
                }
            }
        } catch (Exception e) {
            log.warn("[WikiTool] Failed to resolve source files: {}", e.getMessage());
        }
        return result;
    }

    private String error(String message) {
        return JSONUtil.createObj().set("error", message).toString();
    }
}
