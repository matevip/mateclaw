package vip.mate.memory.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Structured dream consolidation result returned by consolidate().
 *
 * @author MateClaw Team
 */
public record DreamReport(
        Long id,
        Long agentId,
        DreamMode mode,
        String topic,
        String triggerSource,
        String triggeredBy,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int candidateCount,
        int promotedCount,
        int rejectedCount,
        String memoryDiff,
        String llmReason,
        DreamStatus status,
        String errorMessage,
        List<PromotedEntry> promoted,
        List<RejectedEntry> rejected
) {}
