package vip.mate.memory.fact.extraction;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based fact extractor — parses structured markdown sections
 * into subject-predicate-object triples using regex patterns.
 * <p>
 * Handles formats like:
 * - "## key\ncontent" sections in structured/*.md
 * - "- **key**: value" bullet patterns in MEMORY.md
 *
 * @author MateClaw Team
 */
@Component
public class PatternEntityExtractor implements EntityExtractor {

    private static final Pattern SECTION_HEADER = Pattern.compile("^## (.+)$", Pattern.MULTILINE);
    private static final Pattern KV_BULLET = Pattern.compile("^- \\*\\*(.+?)\\*\\*:\\s*(.+)$", Pattern.MULTILINE);
    private static final Pattern FORGOTTEN_MARKER = Pattern.compile("^> Forgotten:", Pattern.MULTILINE);
    private static final Pattern FEEDBACK_HELPFUL = Pattern.compile("^> UserFeedback: HELPFUL", Pattern.MULTILINE);
    private static final Pattern FEEDBACK_UNHELPFUL = Pattern.compile("^> UserFeedback: UNHELPFUL", Pattern.MULTILINE);

    @Override
    public List<ExtractedFact> extract(Long agentId, String filename, String content) {
        List<ExtractedFact> facts = new ArrayList<>();
        if (content == null || content.isBlank()) return facts;

        // Determine category from filename
        String category = inferCategory(filename);

        // Split into sections, skip any section containing "> Forgotten:" metadata
        String[] sections = content.split("(?=^## )", Pattern.MULTILINE);
        for (String section : sections) {
            // Skip forgotten sections (rfc-038 L3: projection excludes Forgotten metadata)
            if (FORGOTTEN_MARKER.matcher(section).find()) continue;

            // Derive trust from UserFeedback metadata in this section
            double trust = deriveTrust(section);

            // Extract key-value bullets: - **key**: value
            Matcher kvMatcher = KV_BULLET.matcher(section);
            while (kvMatcher.find()) {
                String key = kvMatcher.group(1).trim();
                String value = kvMatcher.group(2).trim();
                if (value.isBlank() || value.equals(":")) continue;
                String sourceRef = filename + "#" + toSlug(key);
                facts.add(new ExtractedFact(sourceRef, category, key, "is", value, 0.9, trust, "pattern"));
            }

            // Extract section heading facts from structured files
            if (filename.startsWith("structured/")) {
                Matcher headerM = SECTION_HEADER.matcher(section);
                if (!headerM.find()) continue;
                String heading = headerM.group(1).trim();
                String body = section.substring(headerM.end()).trim();
                if (body.isBlank()) continue;

                String sourceRef = filename + "#" + toSlug(heading);
                if (facts.stream().anyMatch(f -> f.sourceRef().equals(sourceRef))) continue;

                String firstLine = body.split("\n")[0].replaceAll("^[-*>]+\\s*", "").trim();
                if (firstLine.length() >= 5) {
                    facts.add(new ExtractedFact(sourceRef, category, heading, "has", firstLine, 0.8, trust, "pattern"));
                }
            }
        }

        return facts;
    }

    /**
     * Derive trust score from UserFeedback metadata in a section.
     * Base 0.5, HELPFUL +0.1 each, UNHELPFUL -0.2 each, clamped [0,1].
     */
    private double deriveTrust(String section) {
        double trust = 0.5;
        Matcher helpful = FEEDBACK_HELPFUL.matcher(section);
        while (helpful.find()) trust += 0.1;
        Matcher unhelpful = FEEDBACK_UNHELPFUL.matcher(section);
        while (unhelpful.find()) trust -= 0.2;
        return Math.max(0.0, Math.min(1.0, trust));
    }

    private String inferCategory(String filename) {
        if (filename.contains("user")) return "user_pref";
        if (filename.contains("project")) return "project";
        if (filename.contains("reference")) return "reference";
        if (filename.contains("feedback")) return "feedback";
        return "general";
    }

    private String toSlug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_").replaceAll("^_|_$", "");
    }
}
