package vip.mate.tool.document;

/**
 * Stash freshly-rendered bytes into the {@link GeneratedFileCache} and format
 * the markdown link the tool returns to the LLM.
 *
 * <p>Two locales are exposed because mateclaw's existing convention has the
 * inline render tools speak Chinese and the file-driven render tools speak
 * English. Each variant carries the "do NOT prepend a host" instruction
 * because some models hallucinate a placeholder domain in front of the
 * relative URL when echoing it back.
 */
public final class GeneratedFileLink {

    private GeneratedFileLink() {}

    /**
     * Chinese-language tool result for inline render entry points
     * ({@code renderDocx} / {@code renderXlsx} / {@code renderPptx}).
     *
     * @param typeLabel "文档" / "工作簿" / "演示文稿"
     */
    public static String resultZh(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel) {
        String url = stash(bytes, displayName, mimeType, cache);
        return typeLabel + "已生成：[" + displayName + "](" + url + ")（链接 10 分钟内有效）。\n"
                + "重要：回答用户时**必须**使用上述相对路径 `" + url + "`，"
                + "**不要**添加任何 https://、http:// 域名前缀，前端会自动拼接当前主机。";
    }

    /**
     * English-language tool result for file-driven render entry points
     * ({@code renderDocxFromFile} / {@code renderDocxFromFiles} / etc.).
     *
     * @param typeLabel       "Document" / "Workbook" / "Presentation"
     * @param sourceFileCount number of source markdown files combined into the
     *                        artifact; values {@code > 1} produce a "from N files"
     *                        prefix, {@code 1} produces the plain "generated" prefix
     */
    public static String resultEn(byte[] bytes, String displayName, String mimeType,
                                  GeneratedFileCache cache, String typeLabel,
                                  int sourceFileCount) {
        String url = stash(bytes, displayName, mimeType, cache);
        String prefix = sourceFileCount > 1
                ? typeLabel + " generated from " + sourceFileCount + " files"
                : typeLabel + " generated";
        return prefix + ": [" + displayName + "](" + url + ") (link valid for 10 minutes).\n"
                + "IMPORTANT: when replying to the user you **must** use the relative path `"
                + url + "` verbatim. Do **not** prepend any https://, http:// or domain — "
                + "the frontend will resolve the current host automatically.";
    }

    private static String stash(byte[] bytes, String displayName, String mimeType,
                                GeneratedFileCache cache) {
        String id = cache.put(bytes, displayName, mimeType);
        return "/api/v1/files/generated/" + id;
    }
}
