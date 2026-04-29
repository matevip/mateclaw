package vip.mate.memory.archive;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monthly archive service for DREAMS.md.
 * <p>
 * Moves entries older than archiveKeepDays to monthly archive files
 * at memory/dreams/YYYY-MM.md. Idempotent: already-archived entries
 * are not moved again.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryArchiveService {

    private final WorkspaceFileService workspaceFileService;
    private final MemoryProperties properties;

    private static final Pattern DIARY_HEADER = Pattern.compile(
            "^## (\\d{4}-\\d{2}-\\d{2}) \\d{2}:\\d{2}.*$");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Archive old entries from DREAMS.md to monthly files.
     * First line checks archiveEnabled flag.
     */
    public void archiveOldDreams(Long agentId) {
        if (!properties.getDream().isArchiveEnabled()) return;

        WorkspaceFileEntity dreamsFile = workspaceFileService.getFile(agentId, "DREAMS.md");
        if (dreamsFile == null || dreamsFile.getContent() == null || dreamsFile.getContent().isBlank()) {
            return;
        }

        String content = dreamsFile.getContent();
        LocalDate cutoff = LocalDate.now().minusDays(properties.getDream().getArchiveKeepDays());

        // Split into sections by ## header
        String[] lines = content.split("\n");
        StringBuilder kept = new StringBuilder();
        Map<String, StringBuilder> archives = new LinkedHashMap<>(); // YYYY-MM -> content

        StringBuilder currentSection = new StringBuilder();
        String currentDate = null;
        boolean inHeader = true; // first lines before any ## section

        for (String line : lines) {
            Matcher m = DIARY_HEADER.matcher(line);
            if (m.matches()) {
                // Flush previous section
                flushSection(currentDate, currentSection, cutoff, kept, archives);
                currentDate = m.group(1);
                currentSection = new StringBuilder();
                currentSection.append(line).append("\n");
                inHeader = false;
            } else if (inHeader) {
                kept.append(line).append("\n");
            } else {
                currentSection.append(line).append("\n");
            }
        }
        // Flush last section
        flushSection(currentDate, currentSection, cutoff, kept, archives);

        if (archives.isEmpty()) {
            log.debug("[Memory] No old dream entries to archive for agent={}", agentId);
            return;
        }

        // Write archive files
        for (Map.Entry<String, StringBuilder> entry : archives.entrySet()) {
            String archiveFilename = "memory/dreams/" + entry.getKey() + ".md";
            String existing = readSafe(agentId, archiveFilename);
            String archiveContent = existing.isBlank()
                    ? "# Dreaming Archive " + entry.getKey() + "\n\n" + entry.getValue()
                    : existing + "\n" + entry.getValue();
            workspaceFileService.saveFile(agentId, archiveFilename, archiveContent);
        }

        // Update DREAMS.md with only kept entries
        String newContent = kept.toString().trim();
        if (newContent.isEmpty()) {
            newContent = "# Dreaming 整合日记\n\n> All entries archived.";
        }
        workspaceFileService.saveFile(agentId, "DREAMS.md", newContent);

        int archivedMonths = archives.size();
        log.info("[Memory] Archived dream entries to {} monthly files for agent={}", archivedMonths, agentId);
    }

    private void flushSection(String dateStr, StringBuilder section, LocalDate cutoff,
                              StringBuilder kept, Map<String, StringBuilder> archives) {
        if (dateStr == null || section.length() == 0) return;
        try {
            LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
            if (date.isBefore(cutoff)) {
                String monthKey = dateStr.substring(0, 7); // YYYY-MM
                archives.computeIfAbsent(monthKey, k -> new StringBuilder()).append(section);
            } else {
                kept.append(section);
            }
        } catch (Exception e) {
            // Unparseable date: keep it
            kept.append(section);
        }
    }

    private String readSafe(Long agentId, String filename) {
        try {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
