package vip.mate.memory.fact.projection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.extraction.CompositeEntityExtractor;
import vip.mate.memory.fact.extraction.ExtractedFact;
import vip.mate.memory.fact.model.FactEntity;
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
 * are preserved via select-then-update keyed on (agent_id, source_ref).
 * <p>
 * Only this class may write derived columns to mate_fact (core invariant).
 * Uses MyBatis Plus CRUD (dialect-safe for both H2 and MySQL).
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

        // Upsert all extracted facts (dialect-safe)
        LocalDateTime now = LocalDateTime.now();
        List<String> keepRefs = new ArrayList<>();
        for (ExtractedFact fact : allFacts) {
            upsertDerived(agentId, fact, now);
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
            upsertDerived(agentId, fact, now);
        }
        log.debug("[FactProjection] rebuildOne: agent={}, file={}, facts={}", agentId, filename, facts.size());
        return facts.size();
    }

    /**
     * Dialect-safe upsert: select by (agent_id, source_ref), then insert or update.
     * Preserves accumulated columns (use_count, last_used_at) on update.
     */
    private void upsertDerived(Long agentId, ExtractedFact fact, LocalDateTime now) {
        FactEntity existing = factMapper.selectOne(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getSourceRef, fact.sourceRef())
                        .last("LIMIT 1"));

        if (existing != null) {
            // Update derived columns only; preserve accumulated columns
            existing.setCategory(fact.category());
            existing.setSubject(fact.subject());
            existing.setPredicate(fact.predicate());
            existing.setObjectValue(fact.objectValue());
            existing.setConfidence(fact.confidence());
            existing.setExtractedBy(fact.extractedBy());
            // Apply trust time decay (half-life from config, default 60 days)
            existing.setTrust(applyTimeDecay(existing.getTrust(), existing.getUpdateTime(), now));
            existing.setUpdateTime(now);
            existing.setDeleted(0); // un-delete if previously soft-deleted
            factMapper.updateById(existing);
        } else {
            FactEntity entity = new FactEntity();
            entity.setAgentId(agentId);
            entity.setSourceRef(fact.sourceRef());
            entity.setCategory(fact.category());
            entity.setSubject(fact.subject());
            entity.setPredicate(fact.predicate());
            entity.setObjectValue(fact.objectValue());
            entity.setConfidence(fact.confidence());
            entity.setTrust(0.5);
            entity.setUseCount(0);
            entity.setExtractedBy(fact.extractedBy());
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setDeleted(0);
            factMapper.insert(entity);
        }
    }

    /**
     * Apply exponential time decay to trust score.
     * Formula: trust * 2^(-daysSinceLastUpdate / halfLifeDays)
     * Clamped to [0, 1].
     */
    private double applyTimeDecay(Double currentTrust, LocalDateTime lastUpdate, LocalDateTime now) {
        if (currentTrust == null) return 0.5;
        if (lastUpdate == null) return currentTrust;
        int halfLifeDays = properties.getFact().getTrustHalfLifeDays();
        if (halfLifeDays <= 0) return currentTrust; // decay disabled
        long daysDiff = java.time.Duration.between(lastUpdate, now).toDays();
        if (daysDiff <= 0) return currentTrust;
        double decayed = currentTrust * Math.pow(2.0, -(double) daysDiff / halfLifeDays);
        return Math.max(0.0, Math.min(1.0, decayed));
    }
}
