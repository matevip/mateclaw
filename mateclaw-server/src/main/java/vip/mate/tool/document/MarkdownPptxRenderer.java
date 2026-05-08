package vip.mate.tool.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Render a Markdown string into a PowerPoint .pptx byte array using Apache POI.
 *
 * <p>Convention (Marp-compatible subset):
 * <ul>
 *   <li>{@code ---} on its own line separates slides.</li>
 *   <li>The first {@code # / ## / ###} of a slide becomes the slide title.</li>
 *   <li>Lines starting with {@code - } or {@code * } become bullets.</li>
 *   <li>Other non-blank lines become plain paragraphs.</li>
 *   <li>{@code <!-- ... -->} HTML comments become speaker notes.</li>
 * </ul>
 *
 * <p>Page size: 16:9 widescreen by default (960pt x 540pt). Pass
 * {@code "4:3"} or {@code "STANDARD"} to {@link #render(String, String)} for
 * legacy 4:3 (720pt x 540pt).
 */
@Slf4j
@Component
public class MarkdownPptxRenderer {

    /** {@code ---} alone on a line separates slides (Marp / commonmark thematic break). */
    private static final Pattern SLIDE_BREAK = Pattern.compile("^-{3,}\\s*$");

    /** {@code # / ## / ###} title at the start of a slide. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    /** Bullet item: {@code - foo} or {@code * foo}. */
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");

    /** Speaker note marker: {@code <!-- text -->}. */
    private static final Pattern SPEAKER_NOTE = Pattern.compile("^<!--\\s*(.*?)\\s*-->\\s*$");

    private static final double TITLE_FONT_SIZE = 32.0;
    private static final double BULLET_FONT_SIZE = 20.0;
    private static final double PARAGRAPH_FONT_SIZE = 18.0;

    public byte[] render(String markdown, String aspectRatio) throws IOException {
        if (markdown == null) markdown = "";

        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            ppt.setPageSize(resolvePageSize(aspectRatio));

            List<SlideSpec> slides = parseSlides(markdown);
            if (slides.isEmpty()) {
                // Always produce at least one slide so the file is openable.
                slides.add(new SlideSpec(null, List.of(), null));
            }

            int width = (int) ppt.getPageSize().getWidth();
            int height = (int) ppt.getPageSize().getHeight();
            for (SlideSpec spec : slides) {
                writeSlide(ppt, spec, width, height);
            }

            ppt.write(baos);
            return baos.toByteArray();
        }
    }

    private record SlideSpec(String title, List<BodyLine> body, String speakerNote) {}

    private record BodyLine(boolean bullet, String text) {}

    private List<SlideSpec> parseSlides(String markdown) {
        List<SlideSpec> result = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);

        String currentTitle = null;
        List<BodyLine> currentBody = new ArrayList<>();
        StringBuilder currentNote = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (SLIDE_BREAK.matcher(line).matches()) {
                if (currentTitle != null || !currentBody.isEmpty() || currentNote.length() > 0) {
                    result.add(new SlideSpec(
                            currentTitle, currentBody,
                            currentNote.length() == 0 ? null : currentNote.toString().strip()));
                }
                currentTitle = null;
                currentBody = new ArrayList<>();
                currentNote = new StringBuilder();
                continue;
            }

            var noteMatch = SPEAKER_NOTE.matcher(line);
            if (noteMatch.matches()) {
                if (currentNote.length() > 0) currentNote.append('\n');
                currentNote.append(noteMatch.group(1));
                continue;
            }

            if (line.isEmpty()) {
                if (!currentBody.isEmpty()) {
                    currentBody.add(new BodyLine(false, ""));
                }
                continue;
            }

            var headingMatch = HEADING.matcher(line);
            if (headingMatch.matches() && currentTitle == null && currentBody.isEmpty()) {
                currentTitle = headingMatch.group(2).strip();
                continue;
            }

            var bulletMatch = BULLET.matcher(line);
            if (bulletMatch.matches()) {
                currentBody.add(new BodyLine(true, bulletMatch.group(1).strip()));
                continue;
            }

            currentBody.add(new BodyLine(false, line));
        }

        if (currentTitle != null || !currentBody.isEmpty() || currentNote.length() > 0) {
            result.add(new SlideSpec(
                    currentTitle, currentBody,
                    currentNote.length() == 0 ? null : currentNote.toString().strip()));
        }
        return result;
    }

    private void writeSlide(XMLSlideShow ppt, SlideSpec spec, int slideW, int slideH) {
        XSLFSlide slide = ppt.createSlide();

        int margin = 48;
        int titleY = 36;
        int titleH = spec.title() != null ? 80 : 0;
        int bodyY = titleY + (titleH > 0 ? titleH + 12 : 0);
        int bodyH = slideH - bodyY - margin;

        if (spec.title() != null) {
            XSLFTextBox titleBox = slide.createTextBox();
            titleBox.setAnchor(new Rectangle(margin, titleY, slideW - margin * 2, titleH));
            // POI creates text boxes with one empty paragraph + run; reuse it for the title.
            XSLFTextParagraph titleP = titleBox.getTextParagraphs().get(0);
            XSLFTextRun titleR = titleP.getTextRuns().isEmpty()
                    ? titleP.addNewTextRun()
                    : titleP.getTextRuns().get(0);
            titleR.setText(spec.title());
            titleR.setFontSize(TITLE_FONT_SIZE);
            titleR.setBold(true);
        }

        if (!spec.body().isEmpty()) {
            XSLFTextBox bodyBox = slide.createTextBox();
            bodyBox.setAnchor(new Rectangle(margin + 12, bodyY, slideW - margin * 2 - 12, bodyH));
            // Drop the default empty paragraph so our first body line lines up at the top.
            bodyBox.clearText();

            for (BodyLine bl : spec.body()) {
                XSLFTextParagraph p = bodyBox.addNewTextParagraph();
                if (bl.bullet()) {
                    p.setBullet(true);
                    p.setIndentLevel(0);
                }
                XSLFTextRun r = p.addNewTextRun();
                r.setText(bl.text());
                r.setFontSize(bl.bullet() ? BULLET_FONT_SIZE : PARAGRAPH_FONT_SIZE);
            }
        }

        if (spec.speakerNote() != null && !spec.speakerNote().isBlank()) {
            try {
                slide.getNotes().getPlaceholder(0).setText(spec.speakerNote());
            } catch (Exception e) {
                log.debug("Failed to attach speaker note: {}", e.getMessage());
            }
        }
    }

    /**
     * Resolve a user-supplied aspect-ratio string to a POI {@link Dimension}
     * in points. The default (and value for any unrecognized input) is 16:9.
     */
    private Dimension resolvePageSize(String aspectRatio) {
        if (aspectRatio == null) return new Dimension(960, 540);
        String normalized = aspectRatio.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "4:3", "STANDARD" -> new Dimension(720, 540);
            case "16:9", "WIDE", "WIDESCREEN", "" -> new Dimension(960, 540);
            default -> new Dimension(960, 540);
        };
    }
}
