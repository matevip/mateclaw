package vip.mate.memory.fact.projection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.extraction.CompositeEntityExtractor;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the fact projection from canonical sources.
 * <p>
 * Derived columns are overwritten; accumulated columns (use_count, last_used_at)
 * are preserved via MERGE/upsert keyed on (agent_id, source_ref).
 * <p>
 * Only this class may write derived columns to mate_fact (core invariant).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactProjectionBuilder {

    private final FactMapper factMapper;
    private final WorkspaceFileService workspaceFileService;
    private final CompositeEntityExtractor extractor;
    private final MemoryProperties properties;

    /**
     * Full rebuild for an agent. Extracts facts from all canonical sources,
     * upserts derived columns, and soft-deletes stale entries.
     */
    public int rebuildAll(Long agentId) {
        if (!properties.getFact().isProjectionEnabled()) {
            log.debug("[FactProjection] Projection disabled, skipping rebuildAll for agent={}", agentId);
            return 0;
        }

        List<ExtractedFact> allFacts = new ArrayList<>();

        // Extract from structured/*.md files
        List<WorkspaceFileEntity> files = workspaceFileService.listFiles(agentId);
        for (WorkspaceFileEntity file : files) {
            String filename = file.getFilename();
            if (filename == null) continue;
            if (filename.startsWith("structured/") && filename.endsWith(".md")) {
                WorkspaceFileEntity full = workspaceFileService.getFile(agentId, filename);
                if (full != null && full.getContent() != null && !full.getContent().isBlank()) {
                    allFacts.addAll(extractor.extract(agentId, filename, full.getContent()));
                }
            }
        }

        // Extract from MEMORY.md
        WorkspaceFileEntity memoryFile = workspaceFileService.getFile(agentId, "MEMORY.md");
        if (memoryFile != null && memoryFile.getContent() != null && !memoryFile.getContent().isBlank()) {
            allFacts.addAll(extractor.extract(agentId, "MEMORY.md", memoryFile.getContent()));
        }

        // Upsert all extracted facts
        LocalDateTime now = LocalDateTime.now();
        List<String> keepRefs = new ArrayList<>();
        for (ExtractedFact fact : allFacts) {
            factMapper.upsertDerivedH2(agentId, fact.sourceRef(), fact.category(),
                    fact.subject(), fact.predicate(), fact.objectValue(),
                    fact.confidence(), 0.5, fact.extractedBy(), now, now);
            keepRefs.add(fact.sourceRef());
        }

        // Remove stale facts
        if (!keepRefs.isEmpty()) {
            factMapper.deleteByAgentIdAndSourceRefNotIn(agentId, keepRefs, now);
        }

        log.info("[FactProjection] rebuildAll: agent={}, facts={}", agentId, allFacts.size());
        return allFacts.size();
    }

    /**
     * Incremental rebuild for a single file change.
     */
    public int rebuildOne(Long agentId, String filename, String content) {
        if (!properties.getFact().isProjectionEnabled()) return 0;

        List<ExtractedFact> facts = extractor.extract(agentId, filename, content);
        LocalDateTime now = LocalDateTime.now();
        for (ExtractedFact fact : facts) {
            factMapper.upsertDerivedH2(agentId, fact.sourceRef(), fact.category(),
                    fact.subject(), fact.predicate(), fact.objectValue(),
                    fact.confidence(), 0.5, fact.extractedBy(), now, now);
        }
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, facts={}", agentId, filename, facts.size());
        return facts.size();
    }
}
