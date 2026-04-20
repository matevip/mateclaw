package vip.mate.memory.fact.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.fact.model.FactContradictionEntity;
import vip.mate.memory.fact.model.FactEntity;
import vip.mate.memory.fact.model.FactEntityRefEntity;
import vip.mate.memory.fact.repository.FactMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Query service for the fact projection.
 * Read-only + bumpUseCount (the only accumulated column writer).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactQueryService {

    private final FactMapper factMapper;
    private final vip.mate.memory.fact.repository.FactEntityRefMapper refMapper;
    private final vip.mate.memory.fact.repository.FactContradictionMapper contradictionMapper;

    /**
     * Probe facts by entity name (subject or object match).
     */
    public List<FactEntity> probe(Long agentId, String entity) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, entity)
                                .or().like(FactEntity::getObjectValue, entity))
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 20"));
    }

    /**
     * Find related facts via entity references (multi-hop).
     */
    public List<FactEntity> related(Long agentId, String entity, int hops) {
        // Find fact IDs that reference this entity
        List<FactEntityRefEntity> refs = refMapper.selectList(
                new LambdaQueryWrapper<FactEntityRefEntity>()
                        .like(FactEntityRefEntity::getEntityName, entity)
                        .last("LIMIT 50"));
        List<Long> factIds = refs.stream().map(FactEntityRefEntity::getFactId).distinct().toList();
        if (factIds.isEmpty()) return List.of();

        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .in(FactEntity::getId, factIds)
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 20"));
    }

    /**
     * List unresolved contradictions for an agent.
     */
    public List<FactContradictionEntity> listContradictions(Long agentId) {
        return contradictionMapper.selectList(
                new LambdaQueryWrapper<FactContradictionEntity>()
                        .eq(FactContradictionEntity::getAgentId, agentId)
                        .isNull(FactContradictionEntity::getResolution)
                        .eq(FactContradictionEntity::getDeleted, 0)
                        .orderByDesc(FactContradictionEntity::getCreateTime)
                        .last("LIMIT 50"));
    }

    /**
     * Recall relevant facts for a query (used by FactMemoryProvider.prefetch).
     */
    public List<FactEntity> recallRelevant(Long agentId, String query) {
        return factMapper.selectList(
                new LambdaQueryWrapper<FactEntity>()
                        .eq(FactEntity::getAgentId, agentId)
                        .eq(FactEntity::getDeleted, 0)
                        .and(w -> w.like(FactEntity::getSubject, query)
                                .or().like(FactEntity::getObjectValue, query)
                                .or().like(FactEntity::getPredicate, query))
                        .orderByDesc(FactEntity::getTrust)
                        .last("LIMIT 10"));
    }

    /**
     * Bump use_count for fact IDs (the ONLY writer of accumulated columns).
     */
    public void bumpUseCount(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        factMapper.bumpUseCount(ids, LocalDateTime.now());
    }
}
