package vip.mate.wiki.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiChunkEntity;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RFC-011: 混合检索服务
 * <p>
 * 支持三种模式：
 * <ul>
 *   <li>{@code keyword} — DB LIKE 搜索（现有 WikiPageService.searchPages）</li>
 *   <li>{@code semantic} — chunk 向量 cosine 相似度 → 回溯到 page</li>
 *   <li>{@code hybrid} — 两者融合，RRF (Reciprocal Rank Fusion) 排名</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private final WikiPageService pageService;
    private final WikiChunkService chunkService;
    private final WikiEmbeddingService embeddingService;
    private final WikiProperties properties;

    public enum Mode { KEYWORD, SEMANTIC, HYBRID }

    /**
     * 搜索结果（页面级）
     */
    public record PageHit(Long pageId, String slug, String title, String summary, double score) {}

    /**
     * 搜索结果（chunk 级，语义搜索专用）
     */
    public record ChunkHit(Long chunkId, Long rawId, String snippet, float score) {}

    /**
     * 执行混合搜索，返回页面级结果
     */
    public List<PageHit> searchPages(Long kbId, String query, String modeStr, int topK) {
        Mode mode = parseMode(modeStr);

        List<RankedItem> semantic = List.of();
        List<RankedItem> keyword = List.of();

        if (mode != Mode.KEYWORD && embeddingService.isAvailable()) {
            semantic = semanticSearch(kbId, query, topK * 3);
        }
        if (mode != Mode.SEMANTIC) {
            keyword = keywordSearch(kbId, query, topK * 3);
        }

        // 如果 semantic 不可用（no embedding model），回退到 keyword
        if (mode == Mode.SEMANTIC && semantic.isEmpty()) {
            log.debug("[HybridRetriever] Semantic unavailable, falling back to keyword");
            keyword = keywordSearch(kbId, query, topK * 3);
        }

        List<RankedItem> fused;
        if (mode == Mode.KEYWORD || semantic.isEmpty()) {
            fused = keyword;
        } else if (mode == Mode.SEMANTIC) {
            fused = semantic;
        } else {
            fused = rrfFuse(semantic, keyword, 60);
        }

        // 取 topK，装配 PageHit
        return fused.stream()
                .limit(topK)
                .map(ri -> {
                    WikiPageEntity page = pageService.getById(ri.pageId);
                    if (page == null) return null;
                    return new PageHit(ri.pageId, page.getSlug(), page.getTitle(),
                            page.getSummary(), ri.score);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * chunk 级语义搜索（Agent 直接拿 chunk 片段作为证据）
     */
    public List<ChunkHit> searchChunks(Long kbId, String query, int topK) {
        if (!embeddingService.isAvailable()) return List.of();

        float[] queryVec = embeddingService.embedQuery(query);
        if (queryVec == null) return List.of();

        List<WikiChunkEntity> allChunks = chunkService.listByKbId(kbId);

        return allChunks.stream()
                .filter(c -> c.getEmbedding() != null)
                .map(c -> {
                    float[] chunkVec = WikiEmbeddingService.bytesToFloats(c.getEmbedding());
                    float score = WikiEmbeddingService.cosine(queryVec, chunkVec);
                    String snippet = c.getContent().length() > 300
                            ? c.getContent().substring(0, 300) + "..."
                            : c.getContent();
                    return new ChunkHit(c.getId(), c.getRawId(), snippet, score);
                })
                .sorted(Comparator.comparingDouble(ChunkHit::score).reversed())
                .limit(topK)
                .toList();
    }

    // ==================== 内部方法 ====================

    /** 语义搜索：chunk cosine → 聚合到 page（同页多 chunk 取最高分） */
    private List<RankedItem> semanticSearch(Long kbId, String query, int limit) {
        float[] queryVec = embeddingService.embedQuery(query);
        if (queryVec == null) return List.of();

        List<WikiChunkEntity> allChunks = chunkService.listByKbId(kbId);
        if (allChunks.isEmpty()) return List.of();

        // chunk → score, 然后 需要映射到 page。
        // 当前没有 chunk → page 的直接关联（chunk 只有 rawId）。
        // 走 rawId → 找该 rawId 对应的所有 page（source_raw_ids 含该 rawId）
        // 这是个近似：一个 rawId 可能产出多个 page，都算命中。
        Map<Long, Float> chunkScores = new HashMap<>();
        for (WikiChunkEntity chunk : allChunks) {
            if (chunk.getEmbedding() == null) continue;
            float[] vec = WikiEmbeddingService.bytesToFloats(chunk.getEmbedding());
            float score = WikiEmbeddingService.cosine(queryVec, vec);
            chunkScores.merge(chunk.getRawId(), score, Math::max); // rawId 级聚合
        }

        // rawId → page IDs
        List<WikiPageEntity> allPages = pageService.listByKbId(kbId);
        Map<Long, Double> pageScores = new HashMap<>();
        for (WikiPageEntity page : allPages) {
            String rawIds = page.getSourceRawIds();
            if (rawIds == null) continue;
            // 解析 "[1,2,3]" 格式
            for (String rawIdStr : rawIds.replaceAll("[\\[\\]\\s]", "").split(",")) {
                try {
                    long rawId = Long.parseLong(rawIdStr.trim());
                    Float score = chunkScores.get(rawId);
                    if (score != null) {
                        pageScores.merge(page.getId(), (double) score, Math::max);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return pageScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new RankedItem(e.getKey(), e.getValue()))
                .toList();
    }

    /** 关键词搜索：走现有 DB LIKE */
    private List<RankedItem> keywordSearch(Long kbId, String query, int limit) {
        List<WikiPageEntity> results = pageService.searchPages(kbId, query);
        List<RankedItem> ranked = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), limit); i++) {
            // LIKE 无分数，用倒序排名作为伪分数
            ranked.add(new RankedItem(results.get(i).getId(), 1.0 / (i + 1)));
        }
        return ranked;
    }

    /** RRF 融合：score = Σ 1/(k + rank_i) */
    private List<RankedItem> rrfFuse(List<RankedItem> a, List<RankedItem> b, int k) {
        Map<Long, Double> fused = new HashMap<>();
        for (int i = 0; i < a.size(); i++) fused.merge(a.get(i).pageId, 1.0 / (k + i + 1), Double::sum);
        for (int i = 0; i < b.size(); i++) fused.merge(b.get(i).pageId, 1.0 / (k + i + 1), Double::sum);
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> new RankedItem(e.getKey(), e.getValue()))
                .toList();
    }

    private Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            String defaultMode = properties.getSearchDefaultMode();
            return switch (defaultMode) {
                case "keyword" -> Mode.KEYWORD;
                case "semantic" -> Mode.SEMANTIC;
                default -> Mode.HYBRID;
            };
        }
        return switch (mode.toLowerCase()) {
            case "keyword" -> Mode.KEYWORD;
            case "semantic" -> Mode.SEMANTIC;
            default -> Mode.HYBRID;
        };
    }

    private record RankedItem(Long pageId, double score) {}
}
