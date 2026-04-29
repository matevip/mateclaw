package vip.mate.memory.fact.contradiction;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.repository.FactContradictionMapper;
import vip.mate.memory.fact.repository.FactMapper;
import vip.mate.memory.service.PromotedEntry;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects contradictions between newly promoted facts and existing canonical facts.
 * Called as a synchronous step at the end of MemoryEmergenceService.consolidate
 * (rfc-038 §3.7, decision D11 — NOT an event listener).
 *
 * <p><b>EXPERIMENTAL</b> — Phase 3 L1 uses simple string-overlap detection
 * (same subject, different object). False positives are expected.
 * Gated behind {@code mate.memory.fact.contradiction-check-enabled=false} (default off).
 * Full LLM batch judgment (using contradiction-batch.txt prompt) deferred to Phase 3 L4+.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContradictionDetector {

    private final FactMapper factMapper;
    private final FactContradictionMapper contradictionMapper;
    private final MemoryProperties properties;

    /**
     * Detect contradictions between promoted entries and existing facts.
     * Only runs when contradiction-check-enabled=true.
     *
     * @param agentId  the agent ID
     * @param promoted list of newly promoted entries from this dream
     * @return number of new contradictions detected
     */
    public int detect(Long agentId, List<PromotedEntry> promoted) {
        if (!properties.getFact().isContradictionCheckEnabled()) return 0;
        if (promoted == null || promoted.isEmpty()) return 0;

        // Collect subjects from promoted entries
        Set<String> promotedSubjects = new HashSet<>();
        for (PromotedEntry p : promoted) {
            if (p.snippetPreview() != null) {
                // Use the first meaningful word as subject proxy
                String subject = extractSubjectProxy(p.snippetPreview());
                if (subject != null) promotedSubjects.add(subject);
            }
        }
        if (promotedSubjects.isEmpty()) return 0;

        // Find existing facts with matching subjects
        List<FactEntity> existingFacts = factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> {
                            for (String subj : promotedSubjects) {
                                w.or().like(FactEntity::getSubject, subj);
                            }
                        })
                        .last("LIMIT 100"));

        if (existingFacts.size() < 2) return 0;

        // Simple contradiction detection: same subject, different object values
        int detected = 0;
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < existingFacts.size(); i++) {
            for (int j = i + 1; j < existingFacts.size(); j++) {
                FactEntity a = existingFacts.get(i);
                FactEntity b = existingFacts.get(j);
                if (a.getSubject() != null && a.getSubject().equals(b.getSubject())
                        && a.getObjectValue() != null && b.getObjectValue() != null
                        && !a.getObjectValue().equals(b.getObjectValue())) {
                    // Check if this contradiction pair already exists
                    long existing = contradictionMapper.selectCount(
                            new LambdaQueryWrapper<FactContradictionEntity>()
                                    .eq(FactContradictionEntity::getAgentId, agentId)
                                    .eq(FactContradictionEntity::getFactAId, a.getId())
                                    .eq(FactContradictionEntity::getFactBId, b.getId())
                                    .eq(FactContradictionEntity::getDeleted, 0));
                    if (existing > 0) continue;

                    FactContradictionEntity c = new FactContradictionEntity();
                    c.setAgentId(agentId);
                    c.setFactAId(a.getId());
                    c.setFactBId(b.getId());
                    c.setDescription(String.format("%s: \"%s\" vs \"%s\"",
                            a.getSubject(), truncate(a.getObjectValue(), 80), truncate(b.getObjectValue(), 80)));
                    c.setCreateTime(now);
                    c.setUpdateTime(now);
                    c.setDeleted(0);
                    contradictionMapper.insert(c);
                    detected++;
                }
            }
        }

        if (detected > 0) {
            log.info("[Contradiction] Detected {} new contradictions for agent={}", detected, agentId);
        }
        return detected;
    }

    private String extractSubjectProxy(String snippet) {
        if (snippet == null || snippet.isBlank()) return null;
        // Take the first non-empty meaningful token (skip markdown markers)
        String cleaned = snippet.replaceAll("^[-*>#]+\\s*", "").trim();
        String[] words = cleaned.split("\\s+", 3);
        return words.length > 0 && words[0].length() >= 2 ? words[0] : null;
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
