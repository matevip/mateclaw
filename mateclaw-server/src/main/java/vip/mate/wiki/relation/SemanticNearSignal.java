package vip.mate.wiki.relation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageCitationMapper;
import vip.mate.wiki.service.WikiChunkService;
import vip.mate.wiki.service.WikiEmbeddingService;
import vip.mate.wiki.service.WikiPageService;

import java.util.*;

/**
 * RFC-029: Semantic-near signal (optional, weight = 1.0).
 * Uses chunk embeddings to find semantically similar pages.
 * Silently returns empty when embedding service is unavailable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticNearSignal implements RelationSignalStrategy {

    private static final double THRESHOLD = 0.85;

    private final WikiEmbeddingService embeddingService;
    private final WikiChunkService chunkService;
    private final WikiPageCitationMapper citationMapper;
    private final WikiPageService pageService;

    @Override
    public String signalName() { return "semantic_near"; }

    @Override
    public double weight() { return 1.0; }

    @Override
    public Map<Long, Double> score(Long seedPageId, Long kbId) {
        if (!embeddingService.isAvailable()) return Map.of();

        // Get seed page's chunks and their embeddings
        WikiPageEntity seed = pageService.getById(seedPageId);
        if (seed == null) return Map.of();

        // Collect seed chunk embeddings via citation mapper
        List<Long> seedChunkIds = citationMapper.listWithRawByPageId(seedPageId)
            .stream().map(c -> c.chunkId()).distinct().toList();
        if (seedChunkIds.isEmpty()) return Map.of();

        // Get all chunks in the KB
        List<WikiChunkEntity> allChunks = chunkService.listByKbId(kbId);
        Set<Long> seedChunkSet = new HashSet<>(seedChunkIds);

        // Get seed chunk vectors
        List<float[]> seedVectors = new ArrayList<>();
        for (WikiChunkEntity chunk : allChunks) {
            if (seedChunkSet.contains(chunk.getId()) && chunk.getEmbedding() != null) {
                seedVectors.add(WikiEmbeddingService.bytesToFloats(chunk.getEmbedding()));
            }
        }
        if (seedVectors.isEmpty()) return Map.of();

        // Average seed vectors into a single representative vector
        float[] seedVec = averageVectors(seedVectors);

        // Score all non-seed chunks by cosine similarity, aggregate to page level
        Map<Long, Double> chunkScores = new HashMap<>();
        for (WikiChunkEntity chunk : allChunks) {
            if (seedChunkSet.contains(chunk.getId()) || chunk.getEmbedding() == null) continue;
            float[] vec = WikiEmbeddingService.bytesToFloats(chunk.getEmbedding());
            double sim = WikiEmbeddingService.cosine(seedVec, vec);
            if (sim >= THRESHOLD) {
                chunkScores.merge(chunk.getId(), sim, Math::max);
            }
        }

        // Map chunk scores to page scores via citation
        Map<Long, Double> pageScores = new HashMap<>();
        for (var entry : chunkScores.entrySet()) {
            List<Long> pageIds = citationMapper.listPageIdsByChunkId(entry.getKey());
            for (Long pid : pageIds) {
                if (!pid.equals(seedPageId)) {
                    pageScores.merge(pid, entry.getValue() * weight(), Math::max);
                }
            }
        }
        return pageScores;
    }

    private float[] averageVectors(List<float[]> vectors) {
        if (vectors.size() == 1) return vectors.get(0);
        int dim = vectors.get(0).length;
        float[] avg = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) avg[i] += v[i];
        }
        float norm = 0;
        for (int i = 0; i < dim; i++) {
            avg[i] /= vectors.size();
            norm += avg[i] * avg[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) avg[i] /= norm;
        }
        return avg;
    }
}
