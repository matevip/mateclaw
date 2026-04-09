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
            按关键词搜索页面标题、摘要和正文内容，返回匹配的页面列表及其来源文件。
            """)
    public String wiki_search_pages(
            @ToolParam(description = "当前 Agent 的 ID") Long agentId,
            @ToolParam(description = "搜索关键词") String query) {

        if (query == null || query.isBlank()) {
            return error("query is required");
        }

        Long kbId = resolveKbId(agentId);
        if (kbId == null) {
            return error("No wiki knowledge base found for this agent");
        }

        String queryLower = query.toLowerCase();
        List<WikiPageEntity> allPages = pageService.listByKbIdWithContent(kbId);
        List<WikiPageEntity> matched = allPages.stream()
                .filter(p -> (p.getTitle() != null && p.getTitle().toLowerCase().contains(queryLower))
                        || (p.getSummary() != null && p.getSummary().toLowerCase().contains(queryLower))
                        || (p.getContent() != null && p.getContent().toLowerCase().contains(queryLower)))
                .limit(20)
                .toList();

        JSONArray arr = new JSONArray();
        for (WikiPageEntity page : matched) {
            JSONObject obj = JSONUtil.createObj()
                    .set("title", page.getTitle())
                    .set("slug", page.getSlug())
                    .set("summary", page.getSummary())
                    .set("sourceFiles", resolveSourceFiles(page.getSourceRawIds()));
            boolean titleMatch = page.getTitle() != null && page.getTitle().toLowerCase().contains(queryLower);
            boolean contentMatch = page.getContent() != null && page.getContent().toLowerCase().contains(queryLower);
            obj.set("matchIn", titleMatch ? "title" : contentMatch ? "content" : "summary");
            arr.add(obj);
        }

        return JSONUtil.createObj()
                .set("kbId", kbId)
                .set("query", query)
                .set("matchCount", matched.size())
                .set("pages", arr)
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

    /**
     * 通过 agentId 自动解析关联的知识库 ID
     * <p>
     * 查找逻辑：Agent 专属 KB + 公共 KB（agent_id IS NULL），取第一个。
     */
    private Long resolveKbId(Long agentId) {
        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        if (kbs.isEmpty()) {
            // agentId 为 null 时也尝试查公共 KB
            kbs = kbService.listAll();
        }
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
