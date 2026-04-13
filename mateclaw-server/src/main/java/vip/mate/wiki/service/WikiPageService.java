package vip.mate.wiki.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.repository.WikiPageMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wiki 页面服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiPageService {

    private final WikiPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]]+)]]");

    /** 页面摘要缓存：kbId → (data, expiresAt)。5 分钟 TTL，写操作失效。 */
    private record CachedSummaries(List<WikiPageEntity> data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private final ConcurrentHashMap<Long, CachedSummaries> summaryCache = new ConcurrentHashMap<>();
    private static final long SUMMARY_CACHE_TTL_MS = 5 * 60_000; // 5 分钟

    /**
     * 列出知识库的所有页面（不含 content）
     */
    public List<WikiPageEntity> listByKbId(Long kbId) {
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .orderByAsc(WikiPageEntity::getTitle));
        pages.forEach(p -> p.setContent(null));
        return pages;
    }

    /**
     * 列出知识库所有页面（含 content，用于全文搜索）
     */
    public List<WikiPageEntity> listByKbIdWithContent(Long kbId) {
        return pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .orderByAsc(WikiPageEntity::getTitle));
    }

    /**
     * 列出页面摘要（用于上下文注入和 LLM 消化）。
     * 带 5 分钟 TTL 缓存，写操作自动失效。
     */
    public List<WikiPageEntity> listSummaries(Long kbId) {
        CachedSummaries cached = summaryCache.get(kbId);
        if (cached != null && !cached.isExpired()) {
            return cached.data;
        }
        List<WikiPageEntity> pages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .select(WikiPageEntity::getSlug, WikiPageEntity::getTitle,
                                WikiPageEntity::getSummary, WikiPageEntity::getLastUpdatedBy)
                        .eq(WikiPageEntity::getKbId, kbId)
                        .orderByAsc(WikiPageEntity::getTitle));
        summaryCache.put(kbId, new CachedSummaries(pages, System.currentTimeMillis() + SUMMARY_CACHE_TTL_MS));
        return pages;
    }

    /** 失效指定知识库的摘要缓存（页面增删改时调用） */
    public void evictSummaryCache(Long kbId) {
        summaryCache.remove(kbId);
    }

    /**
     * DB 级别搜索页面（不加载 content CLOB 到 Java 内存）
     */
    public List<WikiPageEntity> searchPages(Long kbId, String query) {
        String escaped = query.toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String pattern = "%" + escaped + "%";
        return pageMapper.searchByKeyword(kbId, pattern);
    }

    public WikiPageEntity getBySlug(Long kbId, String slug) {
        return pageMapper.selectOne(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getSlug, slug));
    }

    public WikiPageEntity getById(Long id) {
        return pageMapper.selectById(id);
    }

    /**
     * 创建新 Wiki 页面
     */
    @Transactional
    public WikiPageEntity createPage(Long kbId, String slug, String title, String content,
                                      String summary, String sourceRawIds) {
        WikiPageEntity entity = new WikiPageEntity();
        entity.setKbId(kbId);
        entity.setSlug(slug);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setSummary(summary);
        entity.setOutgoingLinks(extractLinksAsJson(content));
        entity.setSourceRawIds(sourceRawIds);
        entity.setVersion(1);
        entity.setLastUpdatedBy("ai");
        pageMapper.insert(entity);
        evictSummaryCache(kbId);
        return entity;
    }

    /**
     * AI 更新页面内容（手动编辑的页面不覆盖内容，仅追加来源）
     */
    @Transactional
    public WikiPageEntity updatePageByAi(Long kbId, String slug, String content,
                                          String summary, Long newRawId) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) {
            log.warn("[Wiki] Page not found for AI update: kbId={}, slug={}", kbId, slug);
            return null;
        }

        // 手动编辑的页面：AI 不覆盖内容，仅追加来源 raw id
        if ("manual".equals(existing.getLastUpdatedBy())) {
            log.info("[Wiki] Skipping AI content update for manually edited page: kbId={}, slug={}", kbId, slug);
            if (newRawId != null) {
                List<Long> rawIds = parseSourceRawIds(existing.getSourceRawIds());
                if (!rawIds.contains(newRawId)) {
                    rawIds.add(newRawId);
                    existing.setSourceRawIds(toJson(rawIds));
                    pageMapper.updateById(existing);
                    evictSummaryCache(kbId);
                    return getBySlug(kbId, slug); // 从 DB 重新加载确保一致性
                }
            }
            return existing;
        }

        existing.setContent(content);
        existing.setSummary(summary);
        existing.setOutgoingLinks(extractLinksAsJson(content));
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("ai");

        // 追加新的 source raw id
        if (newRawId != null) {
            List<Long> rawIds = parseSourceRawIds(existing.getSourceRawIds());
            if (!rawIds.contains(newRawId)) {
                rawIds.add(newRawId);
                existing.setSourceRawIds(toJson(rawIds));
            }
        }

        pageMapper.updateById(existing);
        evictSummaryCache(kbId);
        return existing;
    }

    /**
     * 手动更新页面内容
     */
    @Transactional
    public WikiPageEntity updatePageManually(Long kbId, String slug, String content, String summary) {
        WikiPageEntity existing = getBySlug(kbId, slug);
        if (existing == null) {
            throw new IllegalArgumentException("Page not found: " + slug);
        }
        existing.setContent(content);
        existing.setOutgoingLinks(extractLinksAsJson(content));
        existing.setVersion(existing.getVersion() + 1);
        existing.setLastUpdatedBy("manual");
        // 同步更新摘要，防止与 content 漂移
        if (summary != null) {
            existing.setSummary(summary);
        } else {
            // 无显式摘要时，从 content 首段提取
            existing.setSummary(extractFirstParagraph(content));
        }
        pageMapper.updateById(existing);
        return existing;
    }

    /**
     * 从 Markdown 内容提取首段作为摘要
     */
    private String extractFirstParagraph(String content) {
        if (content == null || content.isBlank()) return null;
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() && sb.length() > 0) break; // 空行分段
            if (trimmed.startsWith("#")) continue; // 跳过标题行
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(trimmed);
            }
        }
        String para = sb.toString();
        if (para.length() > 300) para = para.substring(0, 300) + "...";
        return para.isEmpty() ? null : para;
    }

    /**
     * 获取反向链接（哪些页面链接到了这个页面）
     */
    public List<WikiPageEntity> getBacklinks(Long kbId, String slug) {
        // 在 outgoing_links JSON 中搜索包含此 slug 的页面
        List<WikiPageEntity> allPages = pageMapper.selectList(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .ne(WikiPageEntity::getSlug, slug));
        return allPages.stream()
                .filter(p -> p.getOutgoingLinks() != null && p.getOutgoingLinks().contains("\"" + slug + "\""))
                .peek(p -> p.setContent(null))
                .collect(Collectors.toList());
    }

    @Transactional
    public void delete(Long kbId, String slug) {
        pageMapper.delete(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)
                        .eq(WikiPageEntity::getSlug, slug));
        evictSummaryCache(kbId);
    }

    /**
     * 批量删除页面（按 slug 列表）
     */
    @Transactional
    public int batchDelete(Long kbId, List<String> slugs) {
        int count = 0;
        for (String slug : slugs) {
            delete(kbId, slug);
            count++;
        }
        return count;
    }

    /**
     * 删除某材料独占的旧页面（重处理前清理）。
     * 安全策略：只删同时满足以下条件的页面：
     * 1. sourceRawIds 仅包含该 rawId（独占，非共享）
     * 2. lastUpdatedBy != 'manual'（非人工维护）
     * 多来源页面：仅移除该 rawId 引用，保留页面。
     */
    @Transactional
    public int deleteExclusiveBySourceRawId(Long kbId, Long rawId) {
        List<WikiPageEntity> allPages = listByKbId(kbId);
        int deleted = 0;
        for (WikiPageEntity page : allPages) {
            if ("manual".equals(page.getLastUpdatedBy())) continue;
            List<Long> sourceIds = parseSourceRawIds(page.getSourceRawIds());
            if (sourceIds.contains(rawId)) {
                if (sourceIds.size() == 1) {
                    // 独占页面：直接删除
                    delete(kbId, page.getSlug());
                    deleted++;
                } else {
                    // 多来源页面：仅移除该 rawId 引用
                    sourceIds.remove(rawId);
                    page.setSourceRawIds(toJson(sourceIds));
                    pageMapper.updateById(page);
                }
            }
        }
        return deleted;
    }

    public int countByKbId(Long kbId) {
        return Math.toIntExact(pageMapper.selectCount(
                new LambdaQueryWrapper<WikiPageEntity>()
                        .eq(WikiPageEntity::getKbId, kbId)));
    }

    /**
     * 从 Markdown 内容中提取 [[links]] 并返回 JSON 数组
     */
    String extractLinksAsJson(String content) {
        if (content == null) return "[]";
        List<String> links = new ArrayList<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String link = matcher.group(1).trim();
            String slug = toSlug(link);
            if (!links.contains(slug)) {
                links.add(slug);
            }
        }
        return toJson(links);
    }

    /**
     * 将标题转换为 slug（URL 安全标识符）
     */
    public static String toSlug(String title) {
        if (title == null) return "";
        return title.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private List<Long> parseSourceRawIds(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
