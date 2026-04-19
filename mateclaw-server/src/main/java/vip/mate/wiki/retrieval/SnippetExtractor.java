package vip.mate.wiki.retrieval;

/**
 * RFC-032: Extracts a context-aware snippet from page content
 * around a query match point.
 */
public class SnippetExtractor {

    private static final int CONTEXT_CHARS = 150;

    /**
     * Extract a snippet from content centered on the first occurrence of the query.
     * If no exact match is found, returns the first ~300 characters.
     */
    public static String extract(String content, String query) {
        if (content == null || query == null) return null;
        int idx = content.toLowerCase().indexOf(query.toLowerCase());
        if (idx < 0) {
            return content.length() <= CONTEXT_CHARS * 2
                ? content
                : content.substring(0, CONTEXT_CHARS * 2) + "...";
        }
        int start = Math.max(0, idx - CONTEXT_CHARS);
        int end   = Math.min(content.length(), idx + query.length() + CONTEXT_CHARS);
        String snippet = content.substring(start, end);
        if (start > 0) snippet = "..." + snippet;
        if (end < content.length()) snippet = snippet + "...";
        return snippet;
    }
}
