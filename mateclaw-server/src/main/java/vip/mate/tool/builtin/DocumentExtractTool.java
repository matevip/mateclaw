package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档文本提取工具
 * 支持 PDF、DOCX、XLSX、PPTX 等 Office 文档的文本提取
 * 实现 fallback 链：系统命令 -> Java 实现 -> 结构化错误
 *
 * 实现策略：
 * - PDF: pdftotext -> pypdf/pdfplumber (Java 实现)
 * - DOCX: textutil/pandoc -> ZIP XML 解析
 */
@Slf4j
@Component
public class DocumentExtractTool {

    private static final int COMMAND_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_LENGTH = 500000; // 500KB — CLOB column has no size limit
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    @Tool(description = """
        从 Office/PDF 文档中提取文本内容。

        支持的格式：
        - PDF (.pdf)
        - Word (.docx, .doc)
        - Excel (.xlsx, .xls) - 提取为文本表格
        - PowerPoint (.pptx, .ppt)

        提取策略（默认自动选择最优方式）：
        1. 优先使用系统命令（pdftotext, textutil, pandoc 等）
        2. 系统命令不可用时使用纯 Java 实现
        3. PDF 扫描版进入 OCR
        4. 全部失败前用 Apache Tika 兜底（覆盖 SmartArt、共享字符串表等盲区）
        5. 返回详细的提取过程和元数据

        参数 options 可包含：
        - pages: 指定页码范围（如 "1-5" 或 "1,3,5"）
        - preserveLayout: 是否保留布局（默认 true）
        - method: 强制指定提取器，跳过自动 fallback 链。当前支持：
          * "auto"（默认）—— 走完整 fallback 链
          * "tika"  —— 直接用 Apache Tika 抽取，适合 Windows 上没装
                       Poppler/Python 的环境，或验证 Tika 单独是否能解开

        如果提取失败，会返回详细的尝试过程和错误信息
        """)
    public String extract_document_text(
            @ToolParam(description = "文件的绝对路径或相对路径") String filePath,
            @ToolParam(description = "可选参数 JSON，如 {\"pages\": \"1-5\", \"method\": \"tika\"}", required = false) String options) {

        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        List<String> attempts = new ArrayList<>();

        try {
            Path path = Paths.get(filePath).toAbsolutePath().normalize();

            if (!Files.exists(path)) {
                return errorResult(filePath, "文件不存在: " + path, attempts);
            }

            // 解析文件类型
            String mimeType = detectMimeType(path);
            result.set("mimeType", mimeType);

            // RFC-051: method=tika 短路 —— 跳过整条 fallback 链，直接调 Tika。
            // 用于：1) 测试 Tika 集成是否健康；2) 用户明知系统命令不可用、想免去
            // 那一长串失败日志的场景。结果里仍然带 attempts 数组，告知"应用户要求跳过自动链"。
            String forcedMethod = extractOption(options, "method");
            if ("tika".equalsIgnoreCase(forcedMethod)) {
                long t = System.currentTimeMillis();
                String text = TikaExtractor.extract(path);
                attempts.add("user-forced method=tika: skipped automatic fallback chain");
                if (text == null || text.isBlank()) {
                    attempts.add("tika: 失败或不可用 (" + (System.currentTimeMillis() - t) + "ms)");
                    return errorResult(filePath, "Tika 抽取无文本（可能格式不支持或文件损坏）", attempts);
                }
                attempts.add("tika: 成功 (" + (System.currentTimeMillis() - t) + "ms)");

                String capped = text;
                boolean trunc = false;
                if (capped.length() > MAX_OUTPUT_LENGTH) {
                    capped = capped.substring(0, MAX_OUTPUT_LENGTH)
                            + "\n\n... [内容已截断，总长度: " + text.length() + " 字符]";
                    trunc = true;
                }
                result.set("text", capped);
                result.set("method", "tika");
                result.set("pages", estimatePages(text));
                result.set("attempts", attempts);
                result.set("truncated", trunc);
                result.set("success", true);
                log.info("[DocumentExtract] {} 使用 method=tika 强制提取成功，{} 字符",
                        filePath, text.length());
                return JSONUtil.toJsonPrettyStr(result);
            }

            // 根据类型选择提取器
            ExtractedContent content;
            if (mimeType.contains("pdf")) {
                content = extractPdf(path, options, attempts);
            } else if (mimeType.contains("wordprocessingml") || mimeType.contains("msword")) {
                content = extractDocx(path, options, attempts);
            } else if (mimeType.contains("spreadsheetml") || mimeType.contains("excel")) {
                content = extractXlsx(path, options, attempts);
            } else if (mimeType.contains("presentationml") || mimeType.contains("powerpoint")) {
                content = extractPptx(path, options, attempts);
            } else {
                return errorResult(filePath, "不支持的文档类型: " + mimeType, attempts);
            }

            // 截断过长的输出
            String text = content.text();
            boolean truncated = false;
            if (text.length() > MAX_OUTPUT_LENGTH) {
                text = text.substring(0, MAX_OUTPUT_LENGTH) + "\n\n... [内容已截断，总长度: " + content.text().length() + " 字符]";
                truncated = true;
            }

            result.set("text", text);
            result.set("method", content.method());
            result.set("pages", content.pages());
            result.set("attempts", attempts);
            result.set("truncated", truncated);
            result.set("success", true);

            log.info("[DocumentExtract] {} 使用 {} 提取成功，{} 页，{} 字符",
                    filePath, content.method(), content.pages(), content.text().length());

        } catch (Exception e) {
            log.error("[DocumentExtract] 提取失败: {}", e.getMessage(), e);
            return errorResult(filePath, "提取失败: " + e.getMessage(), attempts);
        }

        return JSONUtil.toJsonPrettyStr(result);
    }

    @Tool(description = """
        专门用于提取 PDF 文件文本（extract_document_text 的快捷方式）。

        提取链（按优先级）：
        1. pdftotext (poppler-utils) - 质量最好
        2. pdfimages + OCR - 用于扫描版 PDF
        3. Java 实现的 PDF 解析 - 无需外部依赖

        参数 pages 支持：
        - "1-5" - 提取 1-5 页
        - "1,3,5" - 提取指定页
        - 不传则提取全部
        """)
    public String extract_pdf_text(
            @ToolParam(description = "PDF 文件的绝对路径或相对路径") String filePath,
            @ToolParam(description = "页码范围，如 \"1-5\" 或 \"1,3,5\"", required = false) String pages) {

        String options = pages != null ? "{\"pages\": \"" + pages + "\"}" : null;
        return extract_document_text(filePath, options);
    }

    @Tool(description = """
        专门用于提取 Word 文档文本（extract_document_text 的快捷方式）。

        提取链（按优先级）：
        1. textutil (macOS) / pandoc - 保留格式最好
        2. unzip + 解析 document.xml - 纯 Java 实现

        支持 .docx 和 .doc 格式
        """)
    public String extract_docx_text(
            @ToolParam(description = "Word 文档的绝对路径或相对路径") String filePath) {
        return extract_document_text(filePath, null);
    }

    // ==================== PDF 提取链 ====================

    private ExtractedContent extractPdf(Path path, String options, List<String> attempts) throws Exception {
        // 先获取 PDF 真实页数（不依赖提取出的文本长度反推）
        int realPageCount = getPdfPageCount(path);
        log.info("[DocumentExtract] PDF 真实页数: {} ({})", realPageCount, path.getFileName());

        String bestContent = null;
        String bestMethod = null;

        // 1. 尝试 pdftotext
        long t0 = System.currentTimeMillis();
        String content = tryPdftotext(path, options);
        if (content != null && !content.isBlank()) {
            if (!needsOcr(content, realPageCount)) {
                attempts.add("pdftotext: 成功 (" + (System.currentTimeMillis() - t0) + "ms)");
                return new ExtractedContent(content, "pdftotext", realPageCount > 0 ? realPageCount : estimatePages(content));
            }
            double perPage = realPageCount > 0 ? (double) content.strip().length() / realPageCount : 0;
            attempts.add("pdftotext: 文本过少 (总 " + content.strip().length() + " 字符, "
                    + realPageCount + " 页, 每页 " + String.format("%.0f", perPage) + " 字符)，可能是扫描版");
            bestContent = content;
            bestMethod = "pdftotext";
        } else {
            attempts.add("pdftotext: 失败或不可用");
        }

        // 2. 尝试 Python pdfplumber/pypdf
        long t1 = System.currentTimeMillis();
        content = tryPythonPdfExtractor(path, options);
        if (content != null && !content.isBlank()) {
            if (!needsOcr(content, realPageCount)) {
                attempts.add("python_pdf: 成功 (" + (System.currentTimeMillis() - t1) + "ms)");
                return new ExtractedContent(content, "python_pdfplumber", realPageCount > 0 ? realPageCount : estimatePages(content));
            }
            attempts.add("python_pdf: 文本过少");
            if (bestContent == null || content.strip().length() > bestContent.strip().length()) {
                bestContent = content;
                bestMethod = "python_pdfplumber";
            }
        } else {
            attempts.add("python_pdf: 失败或不可用");
        }

        // 3. Java 实现
        long t2 = System.currentTimeMillis();
        content = extractPdfWithJava(path);
        if (content != null && !content.isBlank()) {
            if (!needsOcr(content, realPageCount)) {
                attempts.add("java_pdf: 成功 (" + (System.currentTimeMillis() - t2) + "ms)");
                return new ExtractedContent(content, "java_pdfbox", realPageCount > 0 ? realPageCount : estimatePages(content));
            }
            attempts.add("java_pdf: 文本过少");
            if (bestContent == null || content.strip().length() > bestContent.strip().length()) {
                bestContent = content;
                bestMethod = "java_pdfbox";
            }
        } else {
            attempts.add("java_pdf: 失败");
        }

        // 4. OCR fallback（扫描版/照片型 PDF）
        log.info("[DocumentExtract] 文本提取不足，尝试 OCR: {}", path.getFileName());
        long t3 = System.currentTimeMillis();
        OcrResult ocrResult = tryOcrExtract(path, attempts);
        if (ocrResult != null && ocrResult.text != null && !ocrResult.text.isBlank()) {
            attempts.add("ocr_tesseract: 成功 (" + (System.currentTimeMillis() - t3) + "ms, "
                    + ocrResult.successPages + "/" + ocrResult.totalPages + " 页识别成功)");
            return new ExtractedContent(ocrResult.text, "ocr_tesseract", ocrResult.totalPages);
        }
        // attempts 已由 tryOcrExtract 内部记录失败原因

        // 5. Tika 兜底（RFC-051 §5.2）：所有命令行 / Python / PDFBox / OCR 都失败时
        // 用 Java 内置的 Tika 再试一次。主要服务于 Windows 没装 Poppler / Python 的桌面用户。
        long t4 = System.currentTimeMillis();
        content = TikaExtractor.extract(path);
        if (content != null && !content.isBlank()) {
            attempts.add("tika: 成功 (" + (System.currentTimeMillis() - t4) + "ms)");
            int pages = realPageCount > 0 ? realPageCount : estimatePages(content);
            return new ExtractedContent(content, "tika", pages);
        }
        attempts.add("tika: 失败或不可用");

        // 返回之前级别的部分结果（如果有）
        if (bestContent != null) {
            log.warn("[DocumentExtract] OCR/Tika 不可用，返回部分文本结果: method={}, length={}",
                    bestMethod, bestContent.strip().length());
            int pages = realPageCount > 0 ? realPageCount : estimatePages(bestContent);
            return new ExtractedContent(bestContent, bestMethod + "_partial", pages);
        }

        throw new Exception("所有 PDF 提取方法都失败（包括 OCR 与 Tika）");
    }

    /**
     * 获取 PDF 真实页数。优先用 pdfinfo（Poppler 自带），失败则用 Python pypdf。
     * 不依赖提取出的文本长度反推，避免扫描版 PDF 误判页数。
     */
    private int getPdfPageCount(Path path) {
        // 方法 1: pdfinfo（Poppler 附带工具）
        try {
            String output = executeCommand(List.of("pdfinfo", path.toString()));
            if (output != null) {
                for (String line : output.split("\n")) {
                    if (line.toLowerCase().startsWith("pages:")) {
                        String num = line.substring(6).strip();
                        return Integer.parseInt(num);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[DocumentExtract] pdfinfo 获取页数失败: {}", e.getMessage());
        }
        // 方法 2: Python pypdf
        try {
            String script = "import sys; from pypdf import PdfReader; print(len(PdfReader(sys.argv[1]).pages))";
            String result = tryPythonScript(script, path.toString());
            if (result != null && !result.isBlank()) {
                return Integer.parseInt(result.strip());
            }
        } catch (Exception e) {
            log.debug("[DocumentExtract] pypdf 获取页数失败: {}", e.getMessage());
        }
        return 0; // 未知页数
    }

    /**
     * 判断提取到的文本是否太少、需要尝试 OCR。
     * 使用真实页数（来自 getPdfPageCount）计算字符密度，不再依赖 estimatePages 反推。
     * 页数未知（0）时，只看总字符数。
     */
    private boolean needsOcr(String text, int realPageCount) {
        if (text == null || text.isBlank()) return true;
        String stripped = text.strip();
        if (stripped.length() < 20) return true;
        if (realPageCount <= 0) {
            // 页数未知时回退到总字符数判定（保守阈值）
            return stripped.length() < 100;
        }
        double perPage = (double) stripped.length() / realPageCount;
        // 正常文本 PDF 每页至少数百字符；每页不到 30 字符大概率是扫描版
        return perPage < 30;
    }

    /** OCR 结果（含成功/失败页数统计） */
    private record OcrResult(String text, int totalPages, int successPages) {}

    /**
     * OCR 提取：pdftoppm 转图片 + tesseract 识别文字。
     * 仅依赖 Poppler（pdftoppm）和 tesseract 系统命令，不引入新 Python 依赖。
     * 只有至少一页真正识别到文本时才返回有效结果，全部失败返回 null。
     */
    private OcrResult tryOcrExtract(Path pdfPath, List<String> attempts) {
        Path tempDir = null;
        try {
            long startTime = System.currentTimeMillis();
            tempDir = Files.createTempDirectory("mc_ocr_");

            // Step 1: 检查 pdftoppm 可用性
            try {
                executeCommand(List.of("pdftoppm", "-v"));
            } catch (Exception e) {
                attempts.add("ocr: pdftoppm 不可用，无法将 PDF 转为图片");
                log.warn("[DocumentExtract] OCR: pdftoppm 不可用 - {}", e.getMessage());
                return null;
            }

            // Step 2: PDF → PNG（200dpi，足够 OCR 但不过大）
            long t1 = System.currentTimeMillis();
            String pagePrefix = tempDir.resolve("page").toString();
            executeCommand(List.of("pdftoppm", "-png", "-r", "200",
                    pdfPath.toString(), pagePrefix));
            log.info("[DocumentExtract] OCR: pdftoppm 耗时 {}ms", System.currentTimeMillis() - t1);

            File[] pageFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
            if (pageFiles == null || pageFiles.length == 0) {
                attempts.add("ocr: pdftoppm 未生成图片");
                return null;
            }
            java.util.Arrays.sort(pageFiles);

            // Step 3: 检查 tesseract 可用性并探测语言包
            String langParam;
            try {
                String langOutput = executeCommand(List.of("tesseract", "--list-langs"));
                langParam = buildTesseractLangParam(langOutput);
                log.info("[DocumentExtract] OCR: tesseract 可用，语言参数: {}", langParam);
            } catch (Exception e) {
                attempts.add("ocr: tesseract 未安装");
                log.warn("[DocumentExtract] OCR: tesseract 不可用 - {}", e.getMessage());
                return null;
            }

            // Step 4: 对每页执行 OCR，统计成功/失败页数
            long t2 = System.currentTimeMillis();
            StringBuilder ocrText = new StringBuilder();
            int successPages = 0;
            int failedPages = 0;
            for (int i = 0; i < pageFiles.length; i++) {
                ocrText.append("--- Page ").append(i + 1).append(" ---\n");
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add("tesseract");
                    cmd.add(pageFiles[i].getAbsolutePath());
                    cmd.add("stdout");
                    if (langParam != null) {
                        cmd.add("-l");
                        cmd.add(langParam);
                    }
                    String pageText = executeCommand(cmd);
                    if (pageText != null && !pageText.isBlank()) {
                        ocrText.append(pageText.trim()).append("\n\n");
                        successPages++;
                    } else {
                        ocrText.append("[空白页]\n\n");
                        failedPages++;
                    }
                } catch (Exception e) {
                    log.warn("[DocumentExtract] OCR: 页面 {} tesseract 失败: {}", i + 1, e.getMessage());
                    failedPages++;
                    // 不写 [OCR 失败] 占位，避免被上层误判为有效文本
                }
            }
            log.info("[DocumentExtract] OCR: tesseract 处理 {} 页 (成功 {}, 失败 {}), 耗时 {}ms, 总耗时 {}ms",
                    pageFiles.length, successPages, failedPages,
                    System.currentTimeMillis() - t2, System.currentTimeMillis() - startTime);

            // 只有至少一页真正识别到文本时才算成功
            if (successPages == 0) {
                attempts.add("ocr: tesseract 运行但所有 " + pageFiles.length + " 页均未识别到文本");
                return null;
            }

            return new OcrResult(ocrText.toString().trim(), pageFiles.length, successPages);

        } catch (Exception e) {
            log.warn("[DocumentExtract] OCR 失败: {}", e.getMessage());
            attempts.add("ocr: 异常 - " + e.getMessage());
            return null;
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(f -> { try { Files.delete(f); } catch (IOException ignored) {} });
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 从 tesseract --list-langs 输出中构建最优语言参数。
     * 优先 eng+chi_sim，缺中文则只用 eng，都缺则返回 null（用 tesseract 默认）。
     */
    private String buildTesseractLangParam(String listLangsOutput) {
        if (listLangsOutput == null) return null;
        boolean hasEng = listLangsOutput.contains("eng");
        boolean hasChiSim = listLangsOutput.contains("chi_sim");
        if (hasEng && hasChiSim) return "eng+chi_sim";
        if (hasEng) return "eng";
        if (hasChiSim) return "chi_sim";
        return null;  // 用 tesseract 默认语言
    }

    private String tryPdftotext(Path path, String options) {
        try {
            List<String> command = new ArrayList<>();
            command.add("pdftotext");
            command.add("-layout"); // 保留布局

            // 解析页码选项
            if (options != null && options.contains("pages")) {
                // 简单解析，实际项目中可以更完善
                String pages = extractOption(options, "pages");
                if (pages != null && pages.contains("-")) {
                    String[] parts = pages.split("-");
                    command.add("-f");
                    command.add(parts[0].trim());
                    command.add("-l");
                    command.add(parts[1].trim());
                }
            }

            command.add(path.toString());
            command.add("-"); // 输出到 stdout

            return executeCommand(command);
        } catch (Exception e) {
            log.debug("pdftotext 失败: {}", e.getMessage());
            return null;
        }
    }

    private String tryPythonPdfExtractor(Path path, String options) {
        // 尝试 pdfplumber
        String script = """
            import sys
            try:
                import pdfplumber
                with pdfplumber.open(sys.argv[1]) as pdf:
                    text = []
                    for i, page in enumerate(pdf.pages, 1):
                        text.append(f"--- Page {i} ---")
                        text.append(page.extract_text() or "")
                    print("\\n".join(text))
            except Exception as e:
                sys.exit(1)
            """;
        String result = tryPythonScript(script, path.toString());
        if (result != null && !result.isBlank()) return result;

        // 尝试 pypdf
        script = """
            import sys
            try:
                from pypdf import PdfReader
                reader = PdfReader(sys.argv[1])
                text = []
                for i, page in enumerate(reader.pages, 1):
                    text.append(f"--- Page {i} ---")
                    text.append(page.extract_text() or "")
                print("\\n".join(text))
            except Exception as e:
                sys.exit(1)
            """;
        return tryPythonScript(script, path.toString());
    }

    private String extractPdfWithJava(Path path) {
        // 这里使用纯 Java 实现的 PDF 文本提取
        // 由于 PDFBox 依赖较重，我们使用简化的实现
        // 实际项目中可以引入 org.apache.pdfbox:pdfbox 依赖
        try {
            return extractPdfBasic(path);
        } catch (Exception e) {
            log.debug("Java PDF 提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 基础 PDF 文本提取（简化实现）
     * 实际项目中建议使用 Apache PDFBox
     */
    private String extractPdfBasic(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream is = Files.newInputStream(path)) {
            byte[] content = is.readAllBytes();
            String pdfContent = new String(content, java.nio.charset.StandardCharsets.ISO_8859_1);

            // 简单的文本提取：查找 () 中的文本内容
            // 这是简化实现，仅作为 fallback
            int pageNum = 1;
            text.append("--- Page ").append(pageNum).append(" ---\n");

            // 提取 BT...ET 块中的文本
            int start = 0;
            while ((start = pdfContent.indexOf("BT", start)) != -1) {
                int end = pdfContent.indexOf("ET", start);
                if (end == -1) break;

                String block = pdfContent.substring(start, end);
                // 提取 (text) 中的文本
                int parenStart = 0;
                while ((parenStart = block.indexOf('(', parenStart)) != -1) {
                    int parenEnd = block.indexOf(')', parenStart);
                    if (parenEnd == -1) break;
                    String txt = block.substring(parenStart + 1, parenEnd);
                    // 处理转义
                    txt = txt.replace("\\(", "(").replace("\\)", ")")
                             .replace("\\\\", "\\");
                    if (!txt.trim().isEmpty()) {
                        text.append(txt).append(" ");
                    }
                    parenStart = parenEnd + 1;
                }
                start = end + 2;
            }
        }
        return text.toString().trim();
    }

    // ==================== DOCX 提取链 ====================

    private ExtractedContent extractDocx(Path path, String options, List<String> attempts) throws Exception {
        // 1. 尝试 textutil (macOS)
        String content = tryTextutil(path);
        if (content != null && !content.isBlank()) {
            attempts.add("textutil: 成功");
            return new ExtractedContent(content, "textutil", 0);
        }
        attempts.add("textutil: 失败或不可用");

        // 2. 尝试 pandoc
        content = tryPandoc(path);
        if (content != null && !content.isBlank()) {
            attempts.add("pandoc: 成功");
            return new ExtractedContent(content, "pandoc", 0);
        }
        attempts.add("pandoc: 失败或不可用");

        // 3. 尝试 LibreOffice
        content = tryLibreOffice(path);
        if (content != null && !content.isBlank()) {
            attempts.add("libreoffice: 成功");
            return new ExtractedContent(content, "libreoffice", 0);
        }
        attempts.add("libreoffice: 失败或不可用");

        // 4. Java ZIP XML 解析
        content = extractDocxWithJava(path);
        if (content != null && !content.isBlank()) {
            attempts.add("java_zip_xml: 成功");
            return new ExtractedContent(content, "java_zip_xml", 0);
        }
        attempts.add("java_zip_xml: 失败");

        // 5. Tika 兜底（RFC-051 §5.2）—— 当 textutil/pandoc/libreoffice/ZIP-XML 全失败时。
        // Tika 的 Microsoft 模块覆盖到 .docx 内嵌 SmartArt、批注、复杂表格等场景，正好填补
        // 我们手写的 ZIP XML 解析器的盲区。
        content = TikaExtractor.extract(path);
        if (content != null && !content.isBlank()) {
            attempts.add("tika: 成功");
            return new ExtractedContent(content, "tika", 0);
        }
        attempts.add("tika: 失败或不可用");

        throw new Exception("所有 DOCX 提取方法都失败（包括 Tika）");
    }

    private String tryTextutil(Path path) {
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            return null; // textutil 只在 macOS 上可用
        }
        try {
            // textutil 只能输出到文件
            Path tempOutput = Files.createTempFile("extract", ".txt");
            List<String> command = List.of(
                    "textutil",
                    "-convert", "txt",
                    "-output", tempOutput.toString(),
                    path.toString()
            );
            executeCommand(command);
            String content = Files.readString(tempOutput);
            Files.deleteIfExists(tempOutput);
            return content;
        } catch (Exception e) {
            log.debug("textutil 失败: {}", e.getMessage());
            return null;
        }
    }

    private String tryPandoc(Path path) {
        try {
            List<String> command = List.of(
                    "pandoc",
                    path.toString(),
                    "-t", "plain",
                    "--wrap=none"
            );
            return executeCommand(command);
        } catch (Exception e) {
            log.debug("pandoc 失败: {}", e.getMessage());
            return null;
        }
    }

    private String tryLibreOffice(Path path) {
        try {
            Path tempDir = Files.createTempDirectory("libreoffice");
            List<String> command = List.of(
                    "soffice",
                    "--headless",
                    "--convert-to", "txt",
                    "--outdir", tempDir.toString(),
                    path.toString()
            );
            executeCommand(command);

            // 查找生成的 txt 文件
            String baseName = path.getFileName().toString().replaceAll("\\.[^.]+$", "");
            Path outputFile = tempDir.resolve(baseName + ".txt");
            if (Files.exists(outputFile)) {
                String content = Files.readString(outputFile);
                // 清理
                Files.walk(tempDir).forEach(f -> {
                    try { Files.delete(f); } catch (IOException ignored) {}
                });
                return content;
            }
            return null;
        } catch (Exception e) {
            log.debug("libreoffice 失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractDocxWithJava(Path path) throws Exception {
        StringBuilder text = new StringBuilder();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    text.append(extractTextFromDocxXml(xml));
                }
            }
        }

        return text.toString().trim();
    }

    private String extractTextFromDocxXml(String xml) {
        StringBuilder text = new StringBuilder();
        // 简单解析 <w:t> 标签内容
        int start = 0;
        while ((start = xml.indexOf("<w:t", start)) != -1) {
            int tagEnd = xml.indexOf(">", start);
            int closeTag = xml.indexOf("</w:t>", tagEnd);
            if (closeTag == -1) break;

            String txt = xml.substring(tagEnd + 1, closeTag);
            // 处理 XML 实体
            txt = txt.replace("&lt;", "<")
                     .replace("&gt;", ">")
                     .replace("&amp;", "&")
                     .replace("&quot;", "\"");
            text.append(txt);

            // 检查是否是段落结束
            int nextTag = xml.indexOf("<", closeTag);
            if (nextTag != -1 && xml.substring(nextTag, Math.min(nextTag + 6, xml.length())).equals("<w:p>")) {
                text.append("\n");
            }

            start = closeTag + 6;
        }
        return text.toString();
    }

    // ==================== XLSX 提取 ====================

    private ExtractedContent extractXlsx(Path path, String options, List<String> attempts) throws Exception {
        StringBuilder text = new StringBuilder();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("xl/worksheets/sheet") && entry.getName().endsWith(".xml")) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    text.append("--- ").append(entry.getName()).append(" ---\n");
                    text.append(extractTextFromXlsxXml(xml)).append("\n");
                }
            }
        }

        // Our ZIP-XML extractor only reads <v> tags and skips the shared-strings table,
        // so cells full of text labels look "empty". When that happens, fall through to
        // Tika which knows how to resolve the shared-strings indirection.
        if (text.toString().replaceAll("---.*?---", "").strip().isEmpty()) {
            String fallback = TikaExtractor.extract(path);
            if (fallback != null && !fallback.isBlank()) {
                attempts.add("tika: 成功（ZIP-XML 仅有数字 / 共享字符串未解析）");
                return new ExtractedContent(fallback, "tika", 0);
            }
        }

        attempts.add("java_zip_xml: 成功");
        return new ExtractedContent(text.toString(), "java_zip_xml", 0);
    }

    private String extractTextFromXlsxXml(String xml) {
        StringBuilder text = new StringBuilder();
        int start = 0;
        while ((start = xml.indexOf("<v>", start)) != -1) {
            int end = xml.indexOf("</v>", start);
            if (end == -1) break;
            String value = xml.substring(start + 3, end);
            text.append(value).append("\t");
            start = end + 4;
        }
        return text.toString();
    }

    // ==================== PPTX 提取 ====================

    private ExtractedContent extractPptx(Path path, String options, List<String> attempts) throws Exception {
        StringBuilder text = new StringBuilder();
        int slideNum = 1;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(path))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("ppt/slides/slide") && entry.getName().endsWith(".xml")) {
                    String xml = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    text.append("--- Slide ").append(slideNum++).append(" ---\n");
                    text.append(extractTextFromPptxXml(xml)).append("\n\n");
                }
            }
        }

        // Slide layouts with text inside SmartArt / charts / grouped shapes don't surface
        // through the simple <a:t> grep — Tika walks the full DrawingML graph and pulls
        // them out. Only invoke when our walker produced nothing useful.
        if (text.toString().replaceAll("---.*?---", "").strip().isEmpty()) {
            String fallback = TikaExtractor.extract(path);
            if (fallback != null && !fallback.isBlank()) {
                attempts.add("tika: 成功（ZIP-XML 未抓到正文，可能是 SmartArt / 图表）");
                return new ExtractedContent(fallback, "tika", Math.max(0, slideNum - 1));
            }
        }

        attempts.add("java_zip_xml: 成功");
        return new ExtractedContent(text.toString(), "java_zip_xml", Math.max(0, slideNum - 1));
    }

    private String extractTextFromPptxXml(String xml) {
        StringBuilder text = new StringBuilder();
        int start = 0;
        while ((start = xml.indexOf("<a:t>", start)) != -1) {
            int end = xml.indexOf("</a:t>", start);
            if (end == -1) break;
            String txt = xml.substring(start + 5, end);
            text.append(txt).append(" ");
            start = end + 6;
        }
        return text.toString().trim();
    }

    // ==================== 工具方法 ====================

    /**
     * 执行外部命令并返回 stdout 输出。
     * 使用临时文件重定向代替管道，避免以下问题：
     * - 输出超过管道缓冲区（Linux ~64KB, Windows ~4KB）时进程写阻塞 + waitFor 死锁
     * - Windows 子进程继承 pipe handle 导致读取永远不到 EOF
     */
    private String executeCommand(List<String> command) throws Exception {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("mc_extract_", ".tmp");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());
            Process process = pb.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("命令执行超时");
            }

            if (process.exitValue() != 0) {
                String output = Files.readString(outputFile);
                throw new RuntimeException("命令执行失败: " + output);
            }

            return Files.readString(outputFile);
        } finally {
            if (outputFile != null) {
                try { Files.deleteIfExists(outputFile); } catch (IOException ignored) {}
            }
        }
    }

    private String tryPythonScript(String script, String... args) {
        Path tempScript = null;
        try {
            tempScript = Files.createTempFile("extract", ".py");
            Files.writeString(tempScript, script);

            // Windows 通常只有 python，没有 python3
            String pythonCmd = IS_WINDOWS ? "python" : "python3";
            List<String> command = new ArrayList<>();
            command.add(pythonCmd);
            command.add(tempScript.toString());
            for (String arg : args) command.add(arg);
            return executeCommand(command);
        } catch (Exception e) {
            log.debug("Python 脚本失败: {}", e.getMessage());
            return null;
        } finally {
            if (tempScript != null) {
                try { Files.deleteIfExists(tempScript); } catch (IOException ignored) {}
            }
        }
    }

    private String detectMimeType(Path path) {
        // 复用 FileTypeDetectorTool 的逻辑
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".pdf")) return "application/pdf";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (fileName.endsWith(".doc")) return "application/msword";
        if (fileName.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (fileName.endsWith(".xls")) return "application/vnd.ms-excel";
        if (fileName.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (fileName.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        return "application/octet-stream";
    }

    private String extractOption(String options, String key) {
        // 简单 JSON 解析
        try {
            JSONObject json = JSONUtil.parseObj(options);
            return json.getStr(key);
        } catch (Exception e) {
            return null;
        }
    }

    private int estimatePages(String text) {
        // 粗略估计：每页约 3000 字符
        return Math.max(1, text.length() / 3000);
    }

    private String errorResult(String filePath, String message, List<String> attempts) {
        JSONObject result = new JSONObject();
        result.set("filePath", filePath);
        result.set("error", true);
        result.set("message", message);
        result.set("attempts", attempts);
        result.set("success", false);
        return JSONUtil.toJsonPrettyStr(result);
    }

    // 记录类
    private record ExtractedContent(String text, String method, int pages) {}
}
