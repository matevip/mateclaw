package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured memory service — manages typed memory entries stored as
 * workspace files (structured/user.md, structured/feedback.md, etc.).
 * <p>
 * Each file uses Markdown sections as entries:
 * <pre>
 * ## key_name
 * content text
 * > Source: agent | Updated: 2026-04-09
 * </pre>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredMemoryService {

    private static final Set<String> VALID_TYPES = Set.of("user", "feedback", "project", "reference");
    private static final Pattern SECTION_PATTERN = Pattern.compile("^## (.+)$", Pattern.MULTILINE);

    private final WorkspaceFileService workspaceFileService;
    private final ApplicationEventPublisher eventPublisher;

    /** Per-file lock to prevent concurrent read-modify-write on the same file */
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * Store a typed memory entry. Creates or updates the section with the given key.
     * Uses per-file locking to handle concurrent tool calls writing to the same file.
     */
    public void remember(Long agentId, String type, String key, String content, String source) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename);

            String metadata = "> Source: " + (source != null ? source : "agent")
                    + " | Updated: " + LocalDate.now();
            String newSection = "## " + key + "\n" + content.trim() + "\n" + metadata;

            // Check if section already exists → replace
            String existingSection = findSection(fileContent, key);
            String updated;
            if (existingSection != null) {
                updated = fileContent.replace(existingSection, newSection);
            } else {
                // Append new section
                updated = fileContent.isBlank() ? newSection : fileContent.trim() + "\n\n" + newSection;
            }

            workspaceFileService.saveFile(agentId, filename, updated);
            log.info("[StructuredMemory] {} entry '{}' for agent={} (source={})",
                    existingSection != null ? "Updated" : "Added", key, agentId, source);
            // Publish event for SOUL auto-evolution (Phase 2)
            eventPublisher.publishEvent(new MemoryWriteEvent(agentId, filename, "remember", content));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Search entries by type and optional keyword.
     */
    public List<Map<String, String>> recall(Long agentId, String type, String keyword) {
        if (type != null) {
            validateType(type);
        }

        List<String> types = type != null ? List.of(type) : List.copyOf(VALID_TYPES);
        List<Map<String, String>> results = new ArrayList<>();

        for (String t : types) {
            String fileContent = readFileSafe(agentId, toFilename(t));
            if (fileContent.isBlank()) continue;

            Map<String, String> sections = parseSections(fileContent);
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                if (keyword == null || keyword.isBlank()
                        || entry.getKey().toLowerCase().contains(keyword.toLowerCase())
                        || entry.getValue().toLowerCase().contains(keyword.toLowerCase())) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("type", t);
                    item.put("key", entry.getKey());
                    item.put("content", entry.getValue());
                    results.add(item);
                }
            }
        }
        return results;
    }

    /**
     * Remove a memory entry by type and key.
     */
    public boolean forget(Long agentId, String type, String key) {
        validateType(type);
        String filename = toFilename(type);
        String lockKey = agentId + ":" + filename;
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String fileContent = readFileSafe(agentId, filename);
            if (fileContent.isBlank()) return false;

            String section = findSection(fileContent, key);
            if (section == null) return false;

            String updated = fileContent.replace(section, "").trim();
            // Clean up double blank lines
            updated = updated.replaceAll("\n{3,}", "\n\n");
            workspaceFileService.saveFile(agentId, filename, updated);
            log.info("[StructuredMemory] Removed entry '{}' (type={}) for agent={}", key, type, agentId);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * List all entries of a given type.
     */
    public List<Map<String, String>> listEntries(Long agentId, String type) {
        return recall(agentId, type, null);
    }

    /**
     * Build a formatted memory block for system prompt injection.
     * Returns all typed entries formatted as Markdown.
     */
    public String buildMemoryBlock(Long agentId) {
        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        for (String type : List.of("user", "feedback", "project", "reference")) {
            String fileContent = readFileSafe(agentId, toFilename(type));
            if (fileContent.isBlank()) continue;

            Map<String, String> sections = parseSections(fileContent);
            if (sections.isEmpty()) continue;

            if (!hasContent) {
                sb.append("## Structured Memory\n\n");
                hasContent = true;
            }

            sb.append("### ").append(typeDisplayName(type)).append("\n");
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                // Extract just the content line (skip metadata)
                String content = extractContentOnly(entry.getValue());
                sb.append("- **").append(entry.getKey()).append("**: ").append(content).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    // ==================== Internal ====================

    private String toFilename(String type) {
        return "structured/" + type + ".md";
    }

    private void validateType(String type) {
        if (!VALID_TYPES.contains(type)) {
            throw new IllegalArgumentException("Invalid memory type: " + type
                    + ". Must be one of: " + VALID_TYPES);
        }
    }

    private String readFileSafe(Long agentId, String filename) {
        try {
            WorkspaceFileEntity file = workspaceFileService.getFile(agentId, filename);
            return file != null && file.getContent() != null ? file.getContent() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Parse all sections from a Markdown file.
     * Returns map of key → full section content (including metadata line).
     */
    private Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher matcher = SECTION_PATTERN.matcher(content);
        List<int[]> positions = new ArrayList<>();
        List<String> keys = new ArrayList<>();

        while (matcher.find()) {
            positions.add(new int[]{matcher.start(), matcher.end()});
            keys.add(matcher.group(1).trim());
        }

        for (int i = 0; i < positions.size(); i++) {
            int bodyStart = positions.get(i)[1] + 1; // skip newline after header
            int bodyEnd = (i + 1 < positions.size()) ? positions.get(i + 1)[0] : content.length();
            String body = content.substring(bodyStart, bodyEnd).trim();
            sections.put(keys.get(i), body);
        }

        return sections;
    }

    /**
     * Find a complete section by key (header + body), or null if not found.
     */
    private String findSection(String content, String key) {
        String header = "## " + key;
        int idx = content.indexOf(header);
        if (idx < 0) return null;

        // Find the end: next ## header or EOF
        int nextSection = content.indexOf("\n## ", idx + header.length());
        int end = nextSection >= 0 ? nextSection : content.length();
        return content.substring(idx, end).trim();
    }

    /**
     * Extract just the content text, stripping metadata lines (starting with >).
     */
    private String extractContentOnly(String sectionBody) {
        StringBuilder sb = new StringBuilder();
        for (String line : sectionBody.split("\n")) {
            if (!line.startsWith(">") && !line.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(line.trim());
            }
        }
        return sb.toString();
    }

    private String typeDisplayName(String type) {
        return switch (type) {
            case "user" -> "User Profile";
            case "feedback" -> "Feedback";
            case "project" -> "Project";
            case "reference" -> "Reference";
            default -> type;
        };
    }
}
