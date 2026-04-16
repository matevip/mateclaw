package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.repository.WikiChunkMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Wiki chunk 服务
 * <p>
 * RFC-013 最小切片：chunk 持久化 + hash 级增量对账。
 * <ul>
 *   <li>{@link #persistChunks} — 切分后一次性入库，返回 chunk ID 列表供后续流程使用</li>
 *   <li>{@link #reconcile} — 增量对账：hash 相同的 chunk 保留（含 embedding），hash 不同的重建</li>
 *   <li>{@link #deleteByRawId} — 材料删除时级联清理</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiChunkService {

    private final WikiChunkMapper chunkMapper;

    /**
     * 将文本切片列表持久化为 chunk 记录。
     * <p>
     * 如果该 rawId 已有 chunk 记录，走 {@link #reconcile} 增量对账；否则全量插入。
     *
     * @param kbId    知识库 ID
     * @param rawId   原始材料 ID
     * @param chunks  切分后的文本列表（有序）
     * @param offsets 每个 chunk 对应的 [startOffset, endOffset] 数组
     * @return 持久化后的 chunk ID 列表（与 chunks 同序）
     */
    @Transactional
    public List<Long> persistChunks(Long kbId, Long rawId, List<String> chunks, List<int[]> offsets) {
        List<WikiChunkEntity> existing = listByRawId(rawId);

        if (existing.isEmpty()) {
            // 全量插入
            return insertAll(kbId, rawId, chunks, offsets);
        }

        // 增量对账
        return reconcile(kbId, rawId, chunks, offsets, existing);
    }

    /**
     * 增量对账：比对 hash，保留不变的 chunk（保护未来的 embedding），重建变化的。
     *
     * @return 对账后的 chunk ID 列表（与 chunks 同序）
     */
    private List<Long> reconcile(Long kbId, Long rawId, List<String> chunks, List<int[]> offsets,
                                  List<WikiChunkEntity> existing) {
        // 旧 chunk 按 ordinal 索引
        Map<Integer, WikiChunkEntity> oldByOrdinal = new HashMap<>();
        for (WikiChunkEntity e : existing) {
            oldByOrdinal.put(e.getOrdinal(), e);
        }

        List<Long> resultIds = new ArrayList<>(chunks.size());
        Set<Long> retainedIds = new HashSet<>();
        int retained = 0, rebuilt = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i);
            String hash = computeHash(text);
            int[] offset = offsets.get(i);

            WikiChunkEntity old = oldByOrdinal.get(i);
            if (old != null && hash.equals(old.getContentHash())) {
                // hash 相同 → 保留（embedding 等附加数据不丢）
                // 但更新 offset（材料可能在其他位置变了导致偏移变化）
                if (!old.getStartOffset().equals(offset[0]) || !old.getEndOffset().equals(offset[1])) {
                    old.setStartOffset(offset[0]);
                    old.setEndOffset(offset[1]);
                    chunkMapper.updateById(old);
                }
                resultIds.add(old.getId());
                retainedIds.add(old.getId());
                retained++;
            } else {
                // hash 不同或 ordinal 超出旧范围 → 新建
                WikiChunkEntity entity = buildEntity(kbId, rawId, i, text, hash, offset);
                chunkMapper.insert(entity);
                resultIds.add(entity.getId());
                rebuilt++;
            }
        }

        // 删除多余的旧 chunk（数量缩减的情况）
        int deleted = 0;
        for (WikiChunkEntity old : existing) {
            if (!retainedIds.contains(old.getId()) && !resultIds.contains(old.getId())) {
                chunkMapper.deleteById(old.getId());
                deleted++;
            }
        }

        log.info("[WikiChunk] Reconciled raw={}: retained={}, rebuilt={}, deleted={}",
                rawId, retained, rebuilt, deleted);
        return resultIds;
    }

    /**
     * 全量插入
     */
    private List<Long> insertAll(Long kbId, Long rawId, List<String> chunks, List<int[]> offsets) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String text = chunks.get(i);
            String hash = computeHash(text);
            int[] offset = offsets.get(i);
            WikiChunkEntity entity = buildEntity(kbId, rawId, i, text, hash, offset);
            chunkMapper.insert(entity);
            ids.add(entity.getId());
        }
        log.info("[WikiChunk] Inserted {} chunks for raw={}", chunks.size(), rawId);
        return ids;
    }

    public List<WikiChunkEntity> listByRawId(Long rawId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getRawId, rawId)
                        .orderByAsc(WikiChunkEntity::getOrdinal));
    }

    public List<WikiChunkEntity> listByKbId(Long kbId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId)
                        .orderByAsc(WikiChunkEntity::getRawId)
                        .orderByAsc(WikiChunkEntity::getOrdinal));
    }

    @Transactional
    public void deleteByRawId(Long rawId) {
        int deleted = chunkMapper.delete(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getRawId, rawId));
        if (deleted > 0) {
            log.info("[WikiChunk] Deleted {} chunks for raw={}", deleted, rawId);
        }
    }

    @Transactional
    public void deleteByKbId(Long kbId) {
        int deleted = chunkMapper.delete(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId));
        if (deleted > 0) {
            log.info("[WikiChunk] Deleted {} chunks for kbId={}", deleted, kbId);
        }
    }

    // ==================== Helpers ====================

    private WikiChunkEntity buildEntity(Long kbId, Long rawId, int ordinal, String text, String hash, int[] offset) {
        WikiChunkEntity entity = new WikiChunkEntity();
        entity.setKbId(kbId);
        entity.setRawId(rawId);
        entity.setOrdinal(ordinal);
        entity.setContent(text);
        entity.setCharCount(text.length());
        entity.setStartOffset(offset[0]);
        entity.setEndOffset(offset[1]);
        entity.setContentHash(hash);
        return entity;
    }

    private String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[WikiChunk] Hash computation failed: {}", e.getMessage());
            return "HASH_ERROR_" + content.length();
        }
    }
}
