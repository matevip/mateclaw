package vip.mate.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MorningCardSeenEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MorningCardSeenMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Morning card service — determines whether to show a dream summary card
 * when a user enters an agent view. Scope is per (userId, agentId).
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MorningCardService {

    private final MorningCardSeenMapper seenMapper;
    private final DreamReportMapper dreamReportMapper;

    /**
     * Get the morning card for a user+agent. Returns null if no unseen dream exists.
     */
    public Map<String, Object> getCardFor(Long userId, Long agentId) {
        if (userId == null || agentId == null) return null;
        // Find the latest successful dream report for this agent
        DreamReportEntity latestReport = dreamReportMapper.selectOne(
                new LambdaQueryWrapper<DreamReportEntity>()
                        .eq(DreamReportEntity::getAgentId, agentId)
                        .eq(DreamReportEntity::getStatus, "SUCCESS")
                        .eq(DreamReportEntity::getDeleted, 0)
                        .orderByDesc(DreamReportEntity::getStartedAt)
                        .last("LIMIT 1"));

        if (latestReport == null) {
            return null; // No dream yet
        }

        // Check if user has already seen this report
        MorningCardSeenEntity seen = seenMapper.selectOne(
                new LambdaQueryWrapper<MorningCardSeenEntity>()
                        .eq(MorningCardSeenEntity::getUserId, userId)
                        .eq(MorningCardSeenEntity::getAgentId, agentId));

        if (seen != null && seen.getLastReportId() != null
                && seen.getLastReportId().equals(latestReport.getId())) {
            return null; // Already seen
        }

        // Build card data
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("reportId", latestReport.getId());
        card.put("mode", latestReport.getMode());
        card.put("topic", latestReport.getTopic());
        card.put("startedAt", latestReport.getStartedAt());
        card.put("promotedCount", latestReport.getPromotedCount());
        card.put("rejectedCount", latestReport.getRejectedCount());
        card.put("llmReason", latestReport.getLlmReason());
        card.put("memoryDiff", latestReport.getMemoryDiff());
        return card;
    }

    /**
     * Mark the morning card as seen for a user+agent.
     */
    public void markSeen(Long userId, Long agentId, Long reportId) {
        MorningCardSeenEntity existing = seenMapper.selectOne(
                new LambdaQueryWrapper<MorningCardSeenEntity>()
                        .eq(MorningCardSeenEntity::getUserId, userId)
                        .eq(MorningCardSeenEntity::getAgentId, agentId));

        if (existing != null) {
            existing.setLastSeenAt(LocalDateTime.now());
            existing.setLastReportId(reportId);
            existing.setUpdateTime(LocalDateTime.now());
            seenMapper.updateById(existing);
        } else {
            MorningCardSeenEntity entity = new MorningCardSeenEntity();
            entity.setUserId(userId);
            entity.setAgentId(agentId);
            entity.setLastSeenAt(LocalDateTime.now());
            entity.setLastReportId(reportId);
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(LocalDateTime.now());
            seenMapper.insert(entity);
        }
    }
}
