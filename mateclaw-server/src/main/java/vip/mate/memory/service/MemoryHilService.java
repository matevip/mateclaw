package vip.mate.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vip.mate.memory.event.MemoryWriteEvent;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDate;

/**
 * Human-in-the-Loop service for memory editing.
 * <p>
 * When a user edits a memory entry, this service writes it back to MEMORY.md
 * with a hidden metadata marker (<!-- user-edited: YYYY-MM-DD -->) so that
 * future Dream runs do not overwrite user modifications.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryHilService {

    private final WorkspaceFileService workspaceFileService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Edit a section in MEMORY.md identified by key (section heading).
     * Appends user-edited metadata so Dream prompts respect user changes.
     */
    public void editMemoryEntry(Long agentId, String key, String newContent) {
        WorkspaceFileEntity file = workspaceFileService.getFile(agentId, "MEMORY.md");
        if (file == null || file.getContent() == null) {
            log.warn("[HiL] MEMORY.md not found for agent={}", agentId);
            return;
        }

        String memoryContent = file.getContent();
        String sectionHeader = "## " + key;
        int headerIdx = memoryContent.indexOf(sectionHeader);

        if (headerIdx < 0) {
            // Section not found — append as new section
            String metadata = "<!-- user-edited: " + LocalDate.now() + " -->";
            String newSection = "\n\n" + sectionHeader + "\n" + newContent.trim() + "\n" + metadata;
            memoryContent = memoryContent.trim() + newSection;
        } else {
            // Find section boundaries
            int contentStart = memoryContent.indexOf('\n', headerIdx) + 1;
            int nextSection = memoryContent.indexOf("\n## ", contentStart);
            int sectionEnd = nextSection > 0 ? nextSection : memoryContent.length();

            // Replace section content
            String metadata = "<!-- user-edited: " + LocalDate.now() + " -->";
            String replacement = newContent.trim() + "\n" + metadata + "\n";
            memoryContent = memoryContent.substring(0, contentStart) + replacement
                    + memoryContent.substring(sectionEnd);
        }

        workspaceFileService.saveFile(agentId, "MEMORY.md", memoryContent);
        eventPublisher.publishEvent(new MemoryWriteEvent(agentId, "MEMORY.md", "user-edit", newContent));
        log.info("[HiL] User edited MEMORY.md section '{}' for agent={}", key, agentId);
    }
}
