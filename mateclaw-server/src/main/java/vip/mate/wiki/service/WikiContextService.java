package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.List;

/**
 * Wiki 上下文服务
 * <p>
 * 为 Agent 对话构建 Wiki 知识库上下文，注入到系统提示词中。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiContextService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiPageService pageService;
    private final WikiProperties properties;

    /**
     * 构建与用户消息相关的 Wiki 上下文（任务前知识注入）
     * <p>
     * 从用户消息中提取关键词，匹配 Wiki 页面的标题和摘要，
     * 注入 top-3 相关页面的完整内容到 system prompt 中。
     *
     * @param agentId     Agent ID
     * @param userMessage 用户当前消息
     * @return 相关 Wiki 页面内容，如果没有匹配则返回空字符串
     */
    public String buildRelevantContext(Long agentId, String userMessage) {
        if (!properties.isEnabled() || userMessage == null || userMessage.isBlank()) {
            return buildWikiContext(agentId);
        }

        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        if (kbs.isEmpty()) {
            return "";
        }

        // 从用户消息中提取关键词（简单分词：按非字母数字中文分割，过滤短词）
        String[] keywords = userMessage.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", " ")
                .trim()
                .split("\\s+");

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Relevant Wiki Context\n\n");
        sb.append("The following wiki pages are relevant to the user's current question:\n\n");

        int found = 0;
        int maxChars = properties.getMaxContextChars();
        int totalChars = 0;

        for (WikiKnowledgeBaseEntity kb : kbs) {
            if (found >= 3) break;

            List<WikiPageEntity> pages = pageService.listByKbIdWithContent(kb.getId());
            for (WikiPageEntity page : pages) {
                if (found >= 3) break;

                // 计算匹配分数
                String titleLower = page.getTitle() != null ? page.getTitle().toLowerCase() : "";
                String summaryLower = page.getSummary() != null ? page.getSummary().toLowerCase() : "";
                int score = 0;
                for (String kw : keywords) {
                    if (kw.length() < 2) continue;
                    if (titleLower.contains(kw)) score += 3;
                    if (summaryLower.contains(kw)) score += 1;
                }

                if (score > 0) {
                    String content = page.getContent() != null ? page.getContent() : "";
                    if (totalChars + content.length() > maxChars) {
                        content = content.substring(0, Math.max(0, maxChars - totalChars)) + "\n... (truncated)";
                    }
                    sb.append("### [[").append(page.getTitle()).append("]] (`").append(page.getSlug()).append("`)\n\n");
                    sb.append(content).append("\n\n---\n\n");
                    totalChars += content.length();
                    found++;
                }
            }
        }

        if (found == 0) {
            // 没有相关页面匹配，退回全量摘要模式
            return buildWikiContext(agentId);
        }

        return sb.toString();
    }

    /**
     * 构建指定 Agent 关联的 Wiki 上下文
     *
     * @param agentId Agent ID
     * @return Wiki 上下文字符串，如果没有关联知识库或页面则返回空字符串
     */
    public String buildWikiContext(Long agentId) {
        if (!properties.isEnabled()) {
            return "";
        }

        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        if (kbs.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Wiki Knowledge Base\n\n");
        sb.append("You have access to structured wiki knowledge bases. The knowledge base is automatically resolved from your agentId.\n\n");
        sb.append("Wiki tools (only need agentId + slug or query, NO kbId needed):\n");
        sb.append("- `wiki_search_pages(agentId, query)` — full-text search across titles, summaries, and content\n");
        sb.append("- `wiki_read_page(agentId, slug)` — read full page content with source file info\n");
        sb.append("- `wiki_list_pages(agentId)` — list all pages with summaries\n");
        sb.append("- `wiki_trace_source(agentId, slug)` — find which original documents a page was generated from\n");
        sb.append("- `wiki_create_page(agentId, title, content)` — create a new wiki page to save results, reports, or knowledge\n\n");

        int totalChars = 0;
        int maxChars = properties.getMaxContextChars();

        for (WikiKnowledgeBaseEntity kb : kbs) {
            List<WikiPageEntity> pages = pageService.listSummaries(kb.getId());
            if (pages.isEmpty()) continue;

            sb.append("### ").append(kb.getName());
            if (kb.getDescription() != null && !kb.getDescription().isBlank()) {
                sb.append(" — ").append(kb.getDescription());
            }
            sb.append(" (").append(pages.size()).append(" pages)\n\n");

            // 大量页面时只列标题索引（紧凑模式），节省 prompt 空间
            boolean compact = pages.size() > 20;

            if (compact) {
                sb.append("Page index (use `wiki_search_pages` to find relevant pages, `wiki_read_page` to read full content):\n");
                for (WikiPageEntity page : pages) {
                    String line = "- `" + page.getSlug() + "` — " + page.getTitle() + "\n";
                    if (totalChars + line.length() > maxChars) {
                        sb.append("- ... and ").append(pages.size()).append(" total pages (use `wiki_list_pages` to see all)\n");
                        break;
                    }
                    sb.append(line);
                    totalChars += line.length();
                }
            } else {
                sb.append("Available pages:\n");
                for (WikiPageEntity page : pages) {
                    String line = "- **[[" + page.getTitle() + "]]** (`" + page.getSlug() + "`): "
                            + (page.getSummary() != null ? page.getSummary() : "No summary") + "\n";
                    if (totalChars + line.length() > maxChars) {
                        sb.append("- ... and more pages (use `wiki_list_pages` to see all)\n");
                        break;
                    }
                    sb.append(line);
                    totalChars += line.length();
                }
            }

            sb.append("\n");
        }

        String result = sb.toString();
        if (result.contains("Available pages:")) {
            return result;
        }
        return "";
    }
}
