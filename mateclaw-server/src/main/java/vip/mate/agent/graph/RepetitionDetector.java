package vip.mate.agent.graph;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流式输出重复检测器
 * <p>
 * 检测 LLM 流式输出中的退化重复模式（degenerate repetition），
 * 当检测到内容在滑动窗口内高度重复时返回 true，调用方应截断 LLM 流。
 * <p>
 * Two complementary detection paths run on every {@link #appendAndCheck}:
 * <ol>
 *   <li>Character-level n-gram repetition (continuous "X X X X" loops),
 *       which catches the classic degenerate-output failure within a single
 *       LLM stream.</li>
 *   <li>Sentence-level Jaccard similarity over the trailing N sentences,
 *       which catches "two near-identical sentences ~5 sentences apart" —
 *       a softer failure that the character path misses because the loop is
 *       not adjacent.</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
public class RepetitionDetector {

    /** 滑动窗口大小（字符数） */
    private static final int WINDOW_SIZE = 1024;

    /** 最小重复片段长度 */
    private static final int MIN_PATTERN_LEN = 8;

    /** 最大检测的模式长度 */
    private static final int MAX_PATTERN_LEN = 200;

    /** 模式需要连续出现的最小次数才判定为重复 */
    private static final int MIN_REPEATS = 4;

    /** 已累积内容的最小长度才开始检测（避免误判短内容） */
    private static final int MIN_CONTENT_LEN = 200;

    // ===== Sentence-level detection =====

    /**
     * Number of trailing sentences to compare against the prior window. 3
     * tail sentences plus a 10-sentence lookback is enough to catch "ABC
     * (other) ABC (other)" duplication while keeping the cost bounded.
     */
    private static final int SENTENCE_TAIL_COUNT = 3;
    /**
     * Number of historical sentences the tail compares against. Bumped from 10
     * to 30 to catch markdown-list duplication where the model emits the same
     * 4-6 item list twice with a transition sentence in between — at 10 the
     * tail of the second list could not see the corresponding sentence of the
     * first list, leaving the duplicate undetected.
     */
    private static final int SENTENCE_LOOKBACK = 30;
    private static final double JACCARD_THRESHOLD = 0.85;
    /**
     * Lower bound on the per-sentence token count. Below this both the
     * tail and the historical sentence are too short to make Jaccard
     * meaningful (single phrases like "好的。" would otherwise false-positive).
     */
    private static final int SENTENCE_MIN_TOKENS = 6;
    /**
     * Buffer must hold at least this many characters before sentence-level
     * detection runs — keeps the cost off the early hot path.
     */
    private static final int SENTENCE_MIN_BUFFER = 1500;
    /**
     * Sentence delimiters: full-width Chinese punctuation plus ASCII end-of
     * -sentence punctuation and newline. ASCII '.' '!' '?' are NOT treated as
     * sentence boundaries when preceded by a digit — that prevents markdown
     * list ordinals like "1." / "2." / "3." from fragmenting a paragraph into
     * noise sentences, which used to push the tail past SENTENCE_LOOKBACK and
     * mask whole-list duplication. Decimal numbers (e.g. "1.5") are also
     * spared by the same rule, which is the desired behavior.
     */
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("[\\u3002\\uff01\\uff1f\\n]+|(?<!\\d)[.!?]+");

    private final StringBuilder buffer = new StringBuilder();
    private boolean repetitionDetected = false;
    /**
     * Marks repetition detected via the sentence path so callers can tell
     * "char_pattern" from "sentence_repetition" in their warning broadcasts.
     */
    private boolean lastTriggerWasSentence = false;

    /**
     * 追加新的 delta 并检测是否存在重复
     *
     * @param delta 新增的文本片段
     * @return true 表示检测到退化重复，调用方应截断流
     */
    public boolean appendAndCheck(String delta) {
        if (delta == null || delta.isEmpty() || repetitionDetected) {
            return repetitionDetected;
        }

        buffer.append(delta);

        // 内容太短，不检测
        if (buffer.length() < MIN_CONTENT_LEN) {
            return false;
        }

        // Window trim policy: the char-level path only needs the last ~1024
        // chars, but the sentence path benefits from a larger horizon so it
        // can compare against the full 10-sentence lookback. Keep up to
        // SENTENCE_MIN_BUFFER * 2 so the sentence detector has room.
        int retainCap = Math.max(WINDOW_SIZE, SENTENCE_MIN_BUFFER * 2);
        if (buffer.length() > retainCap * 2) {
            buffer.delete(0, buffer.length() - retainCap);
        }

        // 在窗口尾部检测重复模式
        String window = buffer.toString();
        int windowLen = window.length();

        // 从短模式到长模式扫描
        for (int patternLen = MIN_PATTERN_LEN;
             patternLen <= Math.min(MAX_PATTERN_LEN, windowLen / MIN_REPEATS);
             patternLen++) {

            // 取窗口末尾的 pattern
            String pattern = window.substring(windowLen - patternLen);

            // 向前数这个 pattern 连续出现了几次
            int count = 1;
            int pos = windowLen - patternLen * 2;
            while (pos >= 0) {
                String segment = window.substring(pos, pos + patternLen);
                if (segment.equals(pattern)) {
                    count++;
                    pos -= patternLen;
                } else {
                    break;
                }
            }

            if (count >= MIN_REPEATS) {
                // 排除装饰性重复（代码缩进、ASCII 图表、Markdown 分隔线常见）
                if (isDecorativePattern(pattern)) {
                    continue;
                }

                repetitionDetected = true;
                lastTriggerWasSentence = false;
                log.warn("[RepetitionDetector] Detected degenerate repetition: " +
                                "pattern length={}, repeats={}, pattern preview=\"{}\"",
                        patternLen, count,
                        pattern.length() > 50 ? pattern.substring(0, 50) + "..." : pattern);
                return true;
            }
        }

        // Sentence-level path: cheap to skip until the buffer is long enough
        // to actually contain multiple sentences worth comparing.
        if (buffer.length() >= SENTENCE_MIN_BUFFER && checkSentenceRepetition(window)) {
            repetitionDetected = true;
            lastTriggerWasSentence = true;
            return true;
        }

        return false;
    }

    /**
     * Return true when one of the trailing {@link #SENTENCE_TAIL_COUNT}
     * sentences is near-duplicate to one of the previous {@link #SENTENCE_LOOKBACK}
     * sentences (Jaccard over token unigram sets). Both candidates must clear
     * {@link #SENTENCE_MIN_TOKENS} so we don't false-positive on common short
     * acknowledgements.
     */
    private boolean checkSentenceRepetition(String window) {
        String[] split = SENTENCE_SPLIT.split(window);
        // Drop trailing whitespace-only segments produced by the splitter.
        List<String> sentences = new ArrayList<>(split.length);
        for (String s : split) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        if (sentences.size() < 2) {
            return false;
        }

        int total = sentences.size();
        int tailStart = Math.max(0, total - SENTENCE_TAIL_COUNT);
        int lookbackStart = Math.max(0, tailStart - SENTENCE_LOOKBACK);

        for (int i = tailStart; i < total; i++) {
            Set<String> tailTokens = tokenize(sentences.get(i));
            if (tailTokens.size() < SENTENCE_MIN_TOKENS) continue;

            for (int j = lookbackStart; j < tailStart; j++) {
                Set<String> earlierTokens = tokenize(sentences.get(j));
                if (earlierTokens.size() < SENTENCE_MIN_TOKENS) continue;

                double jaccard = jaccard(tailTokens, earlierTokens);
                if (jaccard >= JACCARD_THRESHOLD) {
                    String preview = sentences.get(i);
                    log.warn("[RepetitionDetector] Sentence-level repetition: " +
                                    "tailIdx={}, earlierIdx={}, jaccard={}, preview=\"{}\"",
                            i, j, String.format("%.2f", jaccard),
                            preview.length() > 60 ? preview.substring(0, 60) + "..." : preview);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Cheap tokenizer that treats each Chinese char as its own token and
     * splits ASCII / European text on whitespace. Producing token sets keeps
     * Jaccard symmetric on lengths, which is the property we rely on.
     */
    private static Set<String> tokenize(String sentence) {
        Set<String> tokens = new HashSet<>();
        StringBuilder asciiWord = new StringBuilder();
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (isCjk(c)) {
                if (asciiWord.length() > 0) {
                    tokens.add(asciiWord.toString().toLowerCase());
                    asciiWord.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                asciiWord.append(c);
            } else {
                if (asciiWord.length() > 0) {
                    tokens.add(asciiWord.toString().toLowerCase());
                    asciiWord.setLength(0);
                }
            }
        }
        if (asciiWord.length() > 0) {
            tokens.add(asciiWord.toString().toLowerCase());
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int intersect = 0;
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String t : smaller) {
            if (larger.contains(t)) intersect++;
        }
        int union = a.size() + b.size() - intersect;
        return union == 0 ? 0.0 : (double) intersect / union;
    }

    /**
     * Mark a logical iteration boundary. Buffer is intentionally NOT cleared
     * — sentence-level detection across LLM call boundaries is the whole
     * reason this detector lives on the conversation, not on a single call.
     * The debug log lets fixtures observe that the boundary signal arrived.
     */
    public void markIterationBoundary() {
        log.debug("[RepetitionDetector] iteration boundary marked (buffer chars={})",
                buffer.length());
    }

    /**
     * Returns "sentence_repetition" when the most recent trigger was the
     * Jaccard path; otherwise "char_pattern". Useful for warning broadcasts
     * that need to differentiate the two failure modes.
     */
    public String lastTriggerReason() {
        if (!repetitionDetected) return null;
        return lastTriggerWasSentence ? "sentence_repetition" : "char_pattern";
    }

    /** Number of characters currently held in the detector's buffer. */
    public int bufferLength() {
        return buffer.length();
    }

    /**
     * 判断 pattern 是否为装饰性字符（不应判定为退化重复）。
     * <p>
     * 排除场景：
     * <ul>
     *   <li>纯空白/缩进：{@code "        "}（代码缩进）</li>
     *   <li>单一重复字符：{@code "────────"} {@code "════════"} {@code "--------"} {@code "********"}（分隔线、表格边框）</li>
     *   <li>Box Drawing 字符族：{@code "┌──────┐"} {@code "│      │"}（ASCII 图表）</li>
     * </ul>
     */
    private boolean isDecorativePattern(String pattern) {
        if (pattern.isBlank()) {
            return true; // 纯空白
        }

        // 统计不同的非空白字符种类
        long distinctNonWhitespace = pattern.chars()
                .filter(c -> !Character.isWhitespace(c))
                .distinct()
                .count();

        // 只有 1-2 种不同的非空白字符 → 装饰性（如 "────────" 或 "│      │"）
        if (distinctNonWhitespace <= 2) {
            return true;
        }

        // 检查是否全部是 Box Drawing / 装饰字符
        boolean allDecorative = pattern.chars().allMatch(c ->
                Character.isWhitespace(c)
                || isBoxDrawing(c)
                || "─━│┃┄┅┆┇┈┉┊┋═║╌╍╎╏╔╗╚╝╠╣╦╩╬├┤┬┴┼┌┐└┘".indexOf(c) >= 0
                || "-=_*+|#~<>".indexOf(c) >= 0);
        return allDecorative;
    }

    private boolean isBoxDrawing(int codePoint) {
        // Unicode Box Drawing block: U+2500 – U+257F
        return codePoint >= 0x2500 && codePoint <= 0x257F;
    }

    /**
     * 重置检测器状态
     */
    public void reset() {
        buffer.setLength(0);
        repetitionDetected = false;
        lastTriggerWasSentence = false;
    }

    /**
     * 是否已检测到重复
     */
    public boolean isRepetitionDetected() {
        return repetitionDetected;
    }
}
