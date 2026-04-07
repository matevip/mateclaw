package vip.mate.tool.search;

import lombok.Builder;
import lombok.Data;

/**
 * 统一搜索结果结构 — 所有 provider 返回此格式
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class SearchResult {

    /** 结果标题 */
    private String title;

    /** 结果链接 */
    private String url;

    /** 摘要/片段 */
    private String snippet;

    /** 来源域名 (如 "reuters.com") */
    private String source;

    /** 发布时间（原始字符串，尽可能保留） */
    private String date;

    /** 提供该结果的 provider id */
    private String providerId;

    /**
     * 格式化为 Markdown 行，用于返回给 LLM
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(title != null ? title : "Untitled").append("**");
        if (source != null || date != null) {
            sb.append(" — ");
            if (source != null) sb.append(source);
            if (source != null && date != null) sb.append(" | ");
            if (date != null) sb.append(date);
        }
        sb.append("\n");
        if (snippet != null && !snippet.isBlank()) {
            sb.append(snippet).append("\n");
        }
        if (url != null && !url.isBlank()) {
            sb.append("🔗 ").append(url).append("\n");
        }
        return sb.toString();
    }
}
