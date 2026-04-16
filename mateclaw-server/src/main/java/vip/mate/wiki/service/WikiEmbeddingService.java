package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.repository.WikiChunkMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * RFC-011: Wiki 嵌入服务
 * <p>
 * 使用 Spring AI 的 {@link EmbeddingModel}（DashScope auto-config）对 chunk 做向量化。
 * <ul>
 *   <li>{@link #embedMissingChunks} — 批量嵌入缺失 embedding 的 chunk（材料处理后异步调用）</li>
 *   <li>{@link #embedQuery} — 查询向量化（混合搜索时调用）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final WikiChunkMapper chunkMapper;
    private final WikiProperties properties;
    private final boolean available;

    public WikiEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                 WikiChunkMapper chunkMapper, WikiProperties properties) {
        this.chunkMapper = chunkMapper;
        this.properties = properties;
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        this.embeddingModel = model;
        this.available = model != null;
        if (!available) {
            log.warn("[WikiEmbedding] No EmbeddingModel bean found — semantic search disabled. "
                    + "Ensure spring-ai-alibaba-starter-dashscope is on classpath and DASHSCOPE_API_KEY is set.");
        } else {
            log.info("[WikiEmbedding] EmbeddingModel available: {}", model.getClass().getSimpleName());
        }
    }

    public boolean isAvailable() { return available; }

    /**
     * 批量嵌入指定 KB 中缺失 embedding 的 chunk。
     * <p>
     * 只嵌入 embedding 为 NULL 或 embeddingModel 与当前配置不匹配的 chunk。
     * 模型切换时自动触发全量重嵌（通过 embeddingModel 字段比对）。
     */
    public int embedMissingChunks(Long kbId) {
        if (!available) {
            log.debug("[WikiEmbedding] Skipping — no EmbeddingModel available");
            return 0;
        }

        String modelName = properties.getEmbeddingModel();
        List<WikiChunkEntity> pending = chunkMapper.selectList(
                new LambdaQueryWrapper<WikiChunkEntity>()
                        .eq(WikiChunkEntity::getKbId, kbId)
                        .and(w -> w.isNull(WikiChunkEntity::getEmbedding)
                                   .or().ne(WikiChunkEntity::getEmbeddingModel, modelName)));

        if (pending.isEmpty()) {
            log.debug("[WikiEmbedding] No chunks need embedding for kbId={}", kbId);
            return 0;
        }

        int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
        int total = 0;

        for (int offset = 0; offset < pending.size(); offset += batchSize) {
            List<WikiChunkEntity> batch = pending.subList(offset, Math.min(offset + batchSize, pending.size()));
            try {
                List<String> inputs = batch.stream()
                        .map(WikiChunkEntity::getContent)
                        .toList();

                EmbeddingResponse resp = embeddingModel.call(
                        new EmbeddingRequest(inputs, null));

                for (int i = 0; i < batch.size(); i++) {
                    float[] vec = resp.getResults().get(i).getOutput();
                    WikiChunkEntity chunk = batch.get(i);
                    chunk.setEmbedding(floatsToBytes(vec));
                    chunk.setEmbeddingModel(modelName);
                    chunkMapper.updateById(chunk);
                }
                total += batch.size();
            } catch (Exception e) {
                log.error("[WikiEmbedding] Batch embedding failed (kbId={}, batchSize={}): {}",
                        kbId, batch.size(), e.getMessage());
                // 继续下一批，不中断
            }
        }

        log.info("[WikiEmbedding] Embedded {}/{} chunks for kbId={}, model={}",
                total, pending.size(), kbId, modelName);
        return total;
    }

    /**
     * 查询向量化（混合搜索时调用）
     */
    public float[] embedQuery(String query) {
        if (!available) return null;
        try {
            EmbeddingResponse resp = embeddingModel.call(
                    new EmbeddingRequest(List.of(query), null));
            return resp.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.error("[WikiEmbedding] Query embedding failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 清空指定 KB 的所有 embedding（模型切换时调用）
     */
    public void clearEmbeddings(Long kbId) {
        chunkMapper.update(null, new LambdaUpdateWrapper<WikiChunkEntity>()
                .eq(WikiChunkEntity::getKbId, kbId)
                .set(WikiChunkEntity::getEmbedding, null)
                .set(WikiChunkEntity::getEmbeddingModel, null));
        log.info("[WikiEmbedding] Cleared all embeddings for kbId={}", kbId);
    }

    // ==================== 向量序列化 ====================

    public static byte[] floatsToBytes(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) buf.putFloat(v);
        return buf.array();
    }

    public static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) vec[i] = buf.getFloat();
        return vec;
    }

    /** 余弦相似度 */
    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0 ? 0f : dot / denom;
    }
}
