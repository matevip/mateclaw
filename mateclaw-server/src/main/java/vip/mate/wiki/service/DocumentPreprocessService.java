package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.dto.WikiChunkDraft;
import vip.mate.wiki.model.WikiRawMaterialEntity;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RFC-051 PR-1c: produces {@link WikiChunkDraft}s from a raw material's
 * extracted text, populating the structural metadata columns added in V39
 * ({@code page_number}, {@code token_count}, {@code header_breadcrumb},
 * {@code source_section}).
 * <p>
 * Strategy is intentionally lightweight:
 * <ul>
 *   <li>Pre-scan the normalized text once to build sparse maps from char
 *       offset → header breadcrumb and char offset → PDF page number.</li>
 *   <li>Reuse the existing sentence-boundary chunker (passed in as an
 *       interface from {@link WikiProcessingService}).</li>
 *   <li>For each chunk, look up the breadcrumb and page that were active at
 *       the chunk's {@code startOffset}.</li>
 *   <li>Estimate token count as {@code ceil(charCount / 4.0)}, matching the
 *       backfill heuristic so eager-vs-lazy and old-vs-new chunks share one
 *       scale until a real tokenizer lands in a follow-up.</li>
 * </ul>
 *
 * No Tika integration: existing {@code DocumentExtractTool} already covers
 * the binary formats and the metadata we need for chunks lives inside the
 * extracted text, not in document properties.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPreprocessService {

    private final WikiContentNormalizer normalizer;
    private final WikiProperties properties;

    // Markdown ATX heading: 1-6 leading '#', then a space, then heading text. Setext-style
    // (=== / ---) headings are not handled — DocumentExtractTool emits ATX for everything.
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    // DocumentExtractTool inserts page markers for PDF text extraction. Match a line like
    // `--- Page 12 ---` (case-insensitive, tolerant of extra whitespace). PPTX uses
    // `--- Slide 4 ---`; treat both as a page-number-like signal.
    private static final Pattern PAGE_MARKER = Pattern.compile(
            "^\\s*-{2,}\\s*(?:Page|Slide)\\s+(\\d+)\\s*-{2,}\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Chunker SPI: pluggable so a unit test (or PR-1c follow-up using a smarter
     * splitter) can pass a different boundary algorithm without dragging the
     * whole {@link WikiProcessingService} into preprocessing.
     */
    public interface Chunker {
        List<int[]> split(String text);
    }

    /**
     * Build chunk drafts from already-normalized text. Caller is expected to
     * have routed the raw text through {@link WikiContentNormalizer} first
     * (see {@link #preprocess(WikiRawMaterialEntity, String, Chunker)}).
     */
    public List<WikiChunkDraft> buildDrafts(String text, Chunker chunker) {
        if (text == null || text.isBlank()) return List.of();

        HeaderIndex headerIndex = HeaderIndex.scan(text);
        PageIndex pageIndex = PageIndex.scan(text);

        List<int[]> windows = chunker.split(text);
        List<WikiChunkDraft> drafts = new ArrayList<>(windows.size());
        for (int[] win : windows) {
            int start = win[0];
            int end = win[1];
            String content = text.substring(start, end);
            int chars = content.length();
            int tokens = (int) Math.ceil(chars / 4.0);

            String breadcrumb = headerIndex.breadcrumbAt(start);
            String section = headerIndex.sectionAt(start); // last segment of breadcrumb
            Integer page = pageIndex.pageAt(start);

            drafts.add(new WikiChunkDraft(content, start, end, page, tokens, breadcrumb, section));
        }
        return drafts;
    }

    /**
     * Convenience entry: normalize, then chunk, then attach metadata. Intended
     * for the lazy ingest branch of {@link WikiProcessingService}.
     */
    public List<WikiChunkDraft> preprocess(WikiRawMaterialEntity raw, String extractedText, Chunker chunker) {
        String normalized = normalizer.normalize(raw == null ? null : raw.getSourceType(), extractedText);
        return buildDrafts(normalized, chunker);
    }

    /** Returns the post-normalization text alone, for callers that need to chunk separately. */
    public String normalize(WikiRawMaterialEntity raw, String extractedText) {
        return normalizer.normalize(raw == null ? null : raw.getSourceType(), extractedText);
    }

    @SuppressWarnings("unused") // exposed for future callers; keeps the property accessible.
    public WikiProperties properties() {
        return properties;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Header index — precomputed list of (lineStartOffset, breadcrumb, deepestHeading)
    // sorted by offset. Lookup uses binary search.
    // ────────────────────────────────────────────────────────────────────────

    private static final class HeaderIndex {
        private final int[] starts;
        private final String[] breadcrumbs;
        private final String[] deepest;

        private HeaderIndex(int[] starts, String[] breadcrumbs, String[] deepest) {
            this.starts = starts;
            this.breadcrumbs = breadcrumbs;
            this.deepest = deepest;
        }

        static HeaderIndex scan(String text) {
            List<int[]> offsets = new ArrayList<>();
            List<String> crumbs = new ArrayList<>();
            List<String> last = new ArrayList<>();
            Deque<String[]> stack = new ArrayDeque<>(); // each entry: [level, title]

            int n = text.length();
            int i = 0;
            while (i < n) {
                int lineEnd = text.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = n;
                String line = text.substring(i, lineEnd);
                Matcher m = MARKDOWN_HEADING.matcher(line);
                if (m.matches()) {
                    int level = m.group(1).length();
                    String title = m.group(2).trim();
                    // Pop deeper-or-equal levels.
                    while (!stack.isEmpty() && Integer.parseInt(stack.peek()[0]) >= level) {
                        stack.pop();
                    }
                    stack.push(new String[]{Integer.toString(level), title});
                    String breadcrumb = buildBreadcrumb(stack);
                    offsets.add(new int[]{i});
                    crumbs.add(breadcrumb);
                    last.add(title);
                }
                i = lineEnd + 1;
            }

            int[] starts = new int[offsets.size()];
            String[] breadcrumbs = new String[offsets.size()];
            String[] deepest = new String[offsets.size()];
            for (int k = 0; k < offsets.size(); k++) {
                starts[k] = offsets.get(k)[0];
                breadcrumbs[k] = crumbs.get(k);
                deepest[k] = last.get(k);
            }
            return new HeaderIndex(starts, breadcrumbs, deepest);
        }

        private static String buildBreadcrumb(Deque<String[]> stack) {
            // Stack iterates top-first; build a root-first list for display.
            List<String[]> ordered = new ArrayList<>(stack);
            StringBuilder sb = new StringBuilder();
            for (int k = ordered.size() - 1; k >= 0; k--) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(ordered.get(k)[1]);
            }
            // Cap at the column width of the DB column (1024).
            return sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
        }

        String breadcrumbAt(int offset) {
            int idx = floorIndex(offset);
            return idx < 0 ? null : breadcrumbs[idx];
        }

        String sectionAt(int offset) {
            int idx = floorIndex(offset);
            if (idx < 0) return null;
            String s = deepest[idx];
            return s != null && s.length() > 500 ? s.substring(0, 500) : s;
        }

        private int floorIndex(int offset) {
            // Largest index with starts[idx] <= offset.
            if (starts.length == 0 || offset < starts[0]) return -1;
            int lo = 0, hi = starts.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= offset) { ans = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            return ans;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Page index — same shape as HeaderIndex but for "--- Page N ---" markers.
    // ────────────────────────────────────────────────────────────────────────

    private static final class PageIndex {
        private final int[] starts;
        private final int[] pages;

        private PageIndex(int[] starts, int[] pages) {
            this.starts = starts;
            this.pages = pages;
        }

        static PageIndex scan(String text) {
            List<int[]> matches = new ArrayList<>();
            int n = text.length();
            int i = 0;
            while (i < n) {
                int lineEnd = text.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = n;
                String line = text.substring(i, lineEnd);
                Matcher m = PAGE_MARKER.matcher(line);
                if (m.matches()) {
                    try {
                        int page = Integer.parseInt(m.group(1));
                        matches.add(new int[]{i, page});
                    } catch (NumberFormatException ignored) {
                        // overflow / not a number; ignore — page stays unknown for this region.
                    }
                }
                i = lineEnd + 1;
            }
            int[] starts = new int[matches.size()];
            int[] pages = new int[matches.size()];
            for (int k = 0; k < matches.size(); k++) {
                starts[k] = matches.get(k)[0];
                pages[k] = matches.get(k)[1];
            }
            return new PageIndex(starts, pages);
        }

        Integer pageAt(int offset) {
            if (starts.length == 0 || offset < starts[0]) return null;
            int lo = 0, hi = starts.length - 1, ans = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= offset) { ans = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            return ans < 0 ? null : pages[ans];
        }
    }
}
