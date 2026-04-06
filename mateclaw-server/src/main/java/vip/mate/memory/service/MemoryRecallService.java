package vip.mate.memory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.MemoryRecallMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆召回追踪与评分服务
 * <p>
 * 记录 workspace 文件的召回频率、查询多样性等信号，
 * 计算加权评分用于 Dreaming 记忆整合。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRecallService {

    private final MemoryRecallMapper recallMapper;
    private final MemoryProperties properties;
    private final ObjectMapper objectMapper;

    private static final int MAX_QUERY_HASHES = 32;

    /**
     * 记录一次文件召回
     */
    public void recordRecall(Long agentId, String filename, String snippetText, String userQueryHash) {
        if (agentId == null || filename == null || filename.isBlank()) {
            return;
        }

        // snippet preview 只取前 200 字符（避免对大文件做完整 SHA-256）
        String preview = snippetText != null && snippetText.length() > 200
                ? snippetText.substring(0, 200)
                : snippetText;

        MemoryRecallEntity existing = recallMapper.selectOne(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getFilename, filename)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();

        if (existing != null) {
            existing.setRecallCount(existing.getRecallCount() + 1);
            existing.setDailyCount(existing.getDailyCount() + 1);
            existing.setLastRecalledAt(now);
            existing.setSnippetPreview(preview);

            if (userQueryHash != null) {
                List<String> hashes = parseQueryHashes(existing.getQueryHashes());
                if (!hashes.contains(userQueryHash) && hashes.size() < MAX_QUERY_HASHES) {
                    hashes.add(userQueryHash);
                }
                existing.setQueryHashes(toJson(hashes));
            }

            recallMapper.updateById(existing);
        } else {
            // 防并发：trackRecalls 和 trackActiveRetrieval 可能同时插入同一 filename
            try {
                MemoryRecallEntity entity = new MemoryRecallEntity();
                entity.setAgentId(agentId);
                entity.setFilename(filename);
                entity.setSnippetPreview(preview);
                entity.setRecallCount(1);
                entity.setDailyCount(1);
                entity.setLastRecalledAt(now);
                entity.setPromoted(false);
                entity.setScore(0.0);
                entity.setCreateTime(now);
                entity.setUpdateTime(now);
                entity.setDeleted(0);

                if (userQueryHash != null) {
                    entity.setQueryHashes(toJson(List.of(userQueryHash)));
                }

                recallMapper.insert(entity);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发插入冲突，退化为更新
                log.debug("[MemoryRecall] Concurrent insert for {}, retrying as update", filename);
                recordRecall(agentId, filename, snippetText, userQueryHash);
            }
        }
    }

    /**
     * 重置所有记录的 dailyCount（在每轮 dreaming 开始时调用）
     */
    public void resetDailyCounts(Long agentId) {
        recallMapper.update(null,
                new LambdaUpdateWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .set(MemoryRecallEntity::getDailyCount, 0));
    }

    /**
     * 获取未提升的候选列表
     */
    public List<MemoryRecallEntity> listCandidates(Long agentId) {
        return recallMapper.selectList(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getPromoted, false)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .orderByDesc(MemoryRecallEntity::getScore));
    }

    /**
     * 计算加权评分，返回超过阈值的高分候选
     */
    public List<MemoryRecallEntity> computeScores(Long agentId) {
        List<MemoryRecallEntity> candidates = listCandidates(agentId);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime now = LocalDateTime.now();

        // 预解析 queryHashes，避免重复 JSON 反序列化（每条记录只解析一次）
        Map<Long, List<String>> queryHashCache = new HashMap<>();
        for (MemoryRecallEntity e : candidates) {
            queryHashCache.put(e.getId(), parseQueryHashes(e.getQueryHashes()));
        }

        // 前置硬门控：不满足的直接跳过评分
        int minRecallCount = properties.getEmergenceMinRecallCount();
        int minUniqueQueries = properties.getEmergenceMinUniqueQueries();
        int maxAgeDays = properties.getEmergenceMaxAgeDays();

        candidates = candidates.stream().filter(e -> {
            if (e.getRecallCount() < minRecallCount) return false;
            if (queryHashCache.getOrDefault(e.getId(), Collections.emptyList()).size() < minUniqueQueries) return false;
            if (maxAgeDays > 0 && e.getCreateTime() != null) {
                long ageDays = ChronoUnit.DAYS.between(e.getCreateTime(), now);
                if (ageDays > maxAgeDays) return false;
            }
            return true;
        }).collect(Collectors.toCollection(ArrayList::new));

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 归一化参数
        int maxRecallCount = candidates.stream()
                .mapToInt(MemoryRecallEntity::getRecallCount)
                .max().orElse(1);
        int maxQueryDiversity = candidates.stream()
                .mapToInt(e -> queryHashCache.getOrDefault(e.getId(), Collections.emptyList()).size())
                .max().orElse(1);

        double halfLifeDays = 7.0;
        double threshold = properties.getEmergenceScoreThreshold();

        for (MemoryRecallEntity entry : candidates) {
            double frequency = (double) entry.getRecallCount() / Math.max(maxRecallCount, 1);

            double recency = 0.0;
            if (entry.getLastRecalledAt() != null) {
                long daysSinceRecall = ChronoUnit.DAYS.between(entry.getLastRecalledAt(), now);
                recency = Math.exp(-0.693 * daysSinceRecall / halfLifeDays);
            }

            int queryCount = queryHashCache.getOrDefault(entry.getId(), Collections.emptyList()).size();
            double diversity = (double) queryCount / Math.max(maxQueryDiversity, 1);

            double freshness = computeFreshness(entry.getFilename(), now);

            double velocity = entry.getRecallCount() > 0
                    ? (double) entry.getDailyCount() / entry.getRecallCount()
                    : 0.0;

            entry.setScore(0.30 * frequency + 0.25 * recency + 0.20 * diversity
                    + 0.15 * freshness + 0.10 * velocity);
        }

        // 批量更新分数（一次 SQL 替代 N 次）
        for (MemoryRecallEntity entry : candidates) {
            recallMapper.update(null,
                    new LambdaUpdateWrapper<MemoryRecallEntity>()
                            .eq(MemoryRecallEntity::getId, entry.getId())
                            .set(MemoryRecallEntity::getScore, entry.getScore()));
        }

        return candidates.stream()
                .filter(e -> e.getScore() >= threshold)
                .sorted(Comparator.comparingDouble(MemoryRecallEntity::getScore).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 标记候选为已提升
     */
    public void markPromoted(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        recallMapper.update(null,
                new LambdaUpdateWrapper<MemoryRecallEntity>()
                        .in(MemoryRecallEntity::getId, ids)
                        .set(MemoryRecallEntity::getPromoted, true));
    }

    // ==================== 查询方法（供 API 使用） ====================

    /**
     * 获取 Agent 的 dreaming 统计摘要
     */
    public Map<String, Object> getDreamingStatus(Long agentId) {
        long total = recallMapper.selectCount(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getDeleted, 0));
        long promoted = recallMapper.selectCount(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getPromoted, true)
                        .eq(MemoryRecallEntity::getDeleted, 0));
        long pending = total - promoted;

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("dreamingEnabled", properties.isDreamingEnabled());
        status.put("dreamingCron", properties.getDreamingCron());
        status.put("scoreThreshold", properties.getEmergenceScoreThreshold());
        status.put("minRecallCount", properties.getEmergenceMinRecallCount());
        status.put("minUniqueQueries", properties.getEmergenceMinUniqueQueries());
        status.put("totalRecallEntries", total);
        status.put("promotedCount", promoted);
        status.put("pendingCandidates", pending);
        return status;
    }

    /**
     * 获取带详情的候选列表（供 API 使用）
     */
    public List<Map<String, Object>> listCandidatesWithDetails(Long agentId) {
        List<MemoryRecallEntity> candidates = recallMapper.selectList(
                new LambdaQueryWrapper<MemoryRecallEntity>()
                        .eq(MemoryRecallEntity::getAgentId, agentId)
                        .eq(MemoryRecallEntity::getDeleted, 0)
                        .orderByDesc(MemoryRecallEntity::getScore));

        return candidates.stream().map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("filename", c.getFilename());
            item.put("score", c.getScore());
            item.put("recallCount", c.getRecallCount());
            item.put("dailyCount", c.getDailyCount());
            item.put("queryCount", parseQueryHashes(c.getQueryHashes()).size());
            item.put("promoted", c.getPromoted());
            item.put("lastRecalledAt", c.getLastRecalledAt());
            item.put("snippetPreview", c.getSnippetPreview());
            return item;
        }).collect(Collectors.toList());
    }

    // ==================== 内部工具方法 ====================

    private double computeFreshness(String filename, LocalDateTime now) {
        // 从 "memory/2026-04-01.md" 或 "memory/2026-04-01.md#section" 提取日期
        if (filename == null || !filename.startsWith("memory/")) {
            return 0.5; // 非 daily note 给中间值
        }
        try {
            String datePart = filename.replace("memory/", "");
            // 剥离 #anchor（片段级追踪产生的 section key）
            int hashIdx = datePart.indexOf('#');
            if (hashIdx > 0) {
                datePart = datePart.substring(0, hashIdx);
            }
            datePart = datePart.replace(".md", "");
            String[] parts = datePart.split("-");
            if (parts.length == 3) {
                LocalDateTime fileDate = LocalDateTime.of(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        0, 0);
                long daysAgo = ChronoUnit.DAYS.between(fileDate, now);
                return Math.max(0, 1.0 - (double) daysAgo / 30.0); // 30 天线性衰减
            }
        } catch (Exception ignored) {
        }
        return 0.5;
    }

    private List<String> parseQueryHashes(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

}
