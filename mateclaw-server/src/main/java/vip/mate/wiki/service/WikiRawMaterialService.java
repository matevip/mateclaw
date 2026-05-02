package vip.mate.wiki.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.system.featureflag.FeatureFlagService;
import vip.mate.tool.builtin.DocumentExtractTool;
import vip.mate.tool.image.vision.ImageVisionService;
import vip.mate.tool.image.vision.VisionRequest;
import vip.mate.tool.image.vision.VisionResult;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.event.WikiProcessingEvent;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wiki 原始材料服务
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiRawMaterialService {

    private static final String VISION_FLAG_KEY = "wiki.ocr.enabled";

    private final WikiRawMaterialMapper rawMapper;
    private final WikiKnowledgeBaseService kbService;
    private final WikiProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final DocumentExtractTool documentExtractTool;
    /** RFC-013：删除时级联清理 chunk */
    private final WikiChunkService chunkService;
    private final ImageVisionService imageVisionService;
    private final PdfImageExtractor pdfImageExtractor;
    private final FeatureFlagService featureFlagService;

    /**
     * RFC-012 follow-up #3：从 partial 状态触发的 reprocess 会在此 set 中打标，
     * 供 {@link vip.mate.wiki.service.WikiProcessingService#processRawMaterial(Long, boolean)}
     * 在 claim 之前消费，从而决定是否保留已生成的 exclusive page（续传语义）。
     * <p>
     * 内存态：server 重启会丢，但原 raw 的 status 已被 reprocess 改为 pending，
     * 重启后按正常 pending 流程跑（退化为「不删旧页的全量重跑」，功能不丢失只是
     * 没有走 route 的 "update" 识别路径）。
     */
    private final Set<Long> partialResumeIds = ConcurrentHashMap.newKeySet();

    public List<WikiRawMaterialEntity> listByKbId(Long kbId) {
        List<WikiRawMaterialEntity> list = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .orderByDesc(WikiRawMaterialEntity::getCreateTime));
        // 不返回大文本字段
        list.forEach(r -> {
            r.setOriginalContent(null);
            r.setExtractedText(null);
        });
        return list;
    }

    public WikiRawMaterialEntity getById(Long id) {
        return rawMapper.selectById(id);
    }

    public WikiRawMaterialEntity findBySourcePath(Long kbId, String sourcePath) {
        return rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getSourcePath, sourcePath));
    }

    public List<WikiRawMaterialEntity> listPending(Long kbId) {
        return rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getProcessingStatus, "pending"));
    }

    /**
     * 添加文本类型的原始材料
     */
    @Transactional
    public WikiRawMaterialEntity addText(Long kbId, String title, String content) {
        String hash = computeHash(content);

        // Dedup: reuse any existing row with the same hash in this KB (any status)
        WikiRawMaterialEntity existing = rawMapper.selectOne(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getKbId, kbId)
                        .eq(WikiRawMaterialEntity::getContentHash, hash)
                        .last("LIMIT 1"));
        if (existing != null) {
            return handleDuplicate(existing);
        }

        WikiRawMaterialEntity entity = new WikiRawMaterialEntity();
        entity.setKbId(kbId);
        entity.setTitle(title);
        entity.setSourceType("text");
        entity.setOriginalContent(content);
        entity.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setContentHash(hash);
        entity.setProcessingStatus("pending");
        rawMapper.insert(entity);

        kbService.incrementRawCount(kbId);

        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), kbId));
        }

        log.info("[Wiki] Raw material added: id={}, kbId={}, title={}", entity.getId(), kbId, title);
        return entity;
    }

    /**
     * Adds a file-type raw material (PDF / DOCX / image / ...).
     *
     * <p>Backwards-compatible overload that omits the MIME type. Callers
     * with the upload Content-Type in hand should prefer the four-argument
     * variant — image-routing in particular needs an authoritative MIME so
     * downstream vision providers know what they are decoding.
     */
    @Transactional
    public WikiRawMaterialEntity addFile(Long kbId, String title, String sourceType,
                                          String sourcePath, long fileSize) {
        return addFile(kbId, title, sourceType, null, sourcePath, fileSize);
    }

    /**
     * Adds a file-type raw material with explicit MIME type.
     *
     * @param mimeType Content-Type string from the upload (e.g. {@code image/png});
     *                 may be null if unknown
     */
    @Transactional
    public WikiRawMaterialEntity addFile(Long kbId, String title, String sourceType,
                                          String mimeType, String sourcePath, long fileSize) {
        WikiRawMaterialEntity entity = new WikiRawMaterialEntity();
        entity.setKbId(kbId);
        entity.setTitle(title);
        entity.setSourceType(sourceType);
        entity.setMimeType(mimeType);
        entity.setSourcePath(sourcePath);
        entity.setFileSize(fileSize);
        entity.setProcessingStatus("pending");

        // Compute hash of original upload bytes (for dedup). RFC-051: hash raw bytes
        // directly — the previous `new String(bytes, UTF_8)` round-trip produced unstable
        // hashes for binary files (PDF/Office) because invalid UTF-8 sequences become
        // replacement characters, collapsing distinct files into the same hash.
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(sourcePath));
            entity.setContentHash(computeHashOfBytes(bytes));
        } catch (Exception e) {
            log.warn("[Wiki] Could not compute file hash for dedup: {}", e.getMessage());
        }

        // Dedup: reuse any existing row with the same hash in this KB (any status)
        if (entity.getContentHash() != null) {
            WikiRawMaterialEntity existing = rawMapper.selectOne(
                    new LambdaQueryWrapper<WikiRawMaterialEntity>()
                            .eq(WikiRawMaterialEntity::getKbId, kbId)
                            .eq(WikiRawMaterialEntity::getContentHash, entity.getContentHash())
                            .last("LIMIT 1"));
            if (existing != null) {
                // Clean up the newly uploaded file — we won't use it
                cleanupFile(sourcePath);
                return handleDuplicate(existing);
            }
        }

        rawMapper.insert(entity);
        kbService.incrementRawCount(kbId);

        if (properties.isAutoProcessOnUpload()) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), kbId));
        }

        log.info("[Wiki] Raw file added: id={}, kbId={}, type={}", entity.getId(), kbId, sourceType);
        return entity;
    }

    /**
     * CAS 式抢占：仅当当前状态为 pending 时才更新为 processing。
     *
     * @return true 表示抢占成功，false 表示已被其他线程处理
     */
    @Transactional
    public boolean claimForProcessing(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null || !"pending".equals(entity.getProcessingStatus())) {
            return false;
        }
        entity.setProcessingStatus("processing");
        entity.setErrorMessage(null);
        // RFC-012 M2 v2 UI：新一轮处理开始，清掉上次遗留的进度显示
        entity.setProgressPhase(null);
        entity.setProgressTotal(0);
        entity.setProgressDone(0);
        rawMapper.updateById(entity);
        return true;
    }

    /**
     * RFC-012 M2 v2 UI：更新 wiki 两阶段消化的进度字段。
     * <p>
     * 在 {@code WikiProcessingService.processChunkTwoPhase} 的四个节点被调用：
     * <ul>
     *   <li>方法开头 → {@code phase="route"}, done=0, total=0（进度条显示 indeterminate）</li>
     *   <li>route 返回后 → {@code phase="phase-b"}, done=0, total=N+M（切换到 determinate）</li>
     *   <li>每页 create/merge 成功 → done +1</li>
     *   <li>方法结束 → {@code phase="done"}（UI 会随 status 变成 completed 自动隐藏进度条）</li>
     * </ul>
     */
    @Transactional
    public void updateProgress(Long id, String phase, int done, int total) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setProgressPhase(phase);
        entity.setProgressDone(done);
        entity.setProgressTotal(total);
        rawMapper.updateById(entity);
    }

    @Transactional
    public void updateProcessingStatus(Long id, String status, String errorMessage) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setProcessingStatus(status);
        entity.setErrorMessage(errorMessage);
        if ("completed".equals(status)) {
            entity.setLastProcessedAt(java.time.LocalDateTime.now());
        }
        rawMapper.updateById(entity);
    }

    /**
     * Cache the extracted text for a raw material.
     * <p>
     * RFC-051: this method no longer touches {@code contentHash}. The previous
     * behavior overwrote the original-upload hash with an extracted-text hash,
     * which broke upload dedup (re-uploading the same file would compute a hash
     * over raw bytes but find a row whose hash had been replaced with extracted
     * text). The {@code contentHash} field is now an immutable identity for the
     * uploaded artifact; downstream short-circuiting uses {@code lastProcessedHash}.
     */
    @Transactional
    public void updateExtractedText(Long id, String extractedText) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setExtractedText(extractedText);
        rawMapper.updateById(entity);
    }

    /**
     * 记录本次成功处理时的 content_hash（RFC-012 Change 5 的短路依据）。
     */
    @Transactional
    public void setLastProcessedHash(Long id, String hash) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) return;
        entity.setLastProcessedHash(hash);
        rawMapper.updateById(entity);
    }

    /**
     * 重新处理：重置状态为 pending 并发布事件。
     * <p>
     * 如果之前状态是 {@code partial}，把 rawId 加入 {@link #partialResumeIds}，
     * 下游的 WikiProcessingService 会据此决定是否保留已生成的 exclusive page（续传语义）。
     */
    @Transactional
    public void reprocess(Long id) {
        WikiRawMaterialEntity entity = rawMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Raw material not found: " + id);
        }
        boolean wasPartial = "partial".equals(entity.getProcessingStatus());
        entity.setProcessingStatus("pending");
        entity.setErrorMessage(null);
        rawMapper.updateById(entity);

        if (wasPartial) {
            partialResumeIds.add(id);
            log.info("[Wiki] Raw material queued for PARTIAL RESUME: id={} (existing pages will be kept)", id);
        } else {
            log.info("[Wiki] Raw material queued for reprocessing: id={}", id);
        }
        eventPublisher.publishEvent(new WikiProcessingEvent(this, entity.getId(), entity.getKbId()));
    }

    /**
     * 消费 partial resume 标记：若存在则返回 true 并从 set 中移除（一次性）。
     * <p>
     * 必须在 {@link #claimForProcessing(Long)} 之前调用：claim 会把 status 改成 processing，
     * 此时已无法区分 raw 原本是从 partial 还是从 failed/pending 过来的。
     */
    public boolean consumePartialResumeFlag(Long id) {
        return partialResumeIds.remove(id);
    }

    @Transactional
    public void delete(Long id) {
        rawMapper.deleteById(id);
        // RFC-013：级联清理 chunk，避免语义搜索命中孤儿 chunk
        try {
            if (chunkService != null) {
                chunkService.deleteByRawId(id);
            }
        } catch (Exception e) {
            log.warn("[Wiki] Failed to cascade-delete chunks for raw={}: {}", id, e.getMessage());
        }
    }

    /**
     * 获取可用文本内容
     * <p>
     * 优先级：已缓存的 extractedText → 原始文本 → 调用 DocumentExtractTool 提取二进制文件
     */
    public String getTextContent(WikiRawMaterialEntity entity) {
        // 已有缓存的提取文本
        if (entity.getExtractedText() != null && !entity.getExtractedText().isBlank()) {
            return entity.getExtractedText();
        }
        // 文本类型直接返回原始内容
        if ("text".equals(entity.getSourceType())) {
            return entity.getOriginalContent();
        }
        // Image source: route through the vision-in pipeline. Failures (feature
        // flag off, no provider configured, all providers failed) degrade to an
        // empty caption rather than blocking the upload — the user keeps the
        // raw row and can retry once vision is configured.
        if ("image".equals(entity.getSourceType())) {
            return extractTextFromImage(entity);
        }
        // 二进制文件：调用 DocumentExtractTool 提取
        if (entity.getSourcePath() != null && !entity.getSourcePath().isBlank()) {
            try {
                String result = documentExtractTool.extract_document_text(entity.getSourcePath(), null);
                JSONObject json = JSONUtil.parseObj(result);
                if (json.getBool("success", false)) {
                    String text = json.getStr("text");
                    if (text != null && !text.isBlank()) {
                        boolean truncated = json.getBool("truncated", false);
                        // Append inline-image captions for PDFs so chunk-level search
                        // hits chart/diagram contents that the text extractor missed.
                        // Failures are non-fatal: the body text is still returned.
                        EnrichmentOutcome enrichment = appendPdfImageCaptions(entity, text);

                        if (truncated) {
                            // 截断的结果不缓存，避免永久丢失后半内容。返回文本供分块处理使用。
                            log.warn("[Wiki] Extracted text truncated at {} chars for: {} (full document may be larger)",
                                    text.length(), entity.getSourcePath());
                        } else if (!enrichment.shouldCache()) {
                            // Vision was unavailable but the PDF has images we'd otherwise
                            // caption — leave extracted_text NULL so the next call retries
                            // once the operator enables wiki.ocr.enabled.
                            log.info("[Wiki] PDF id={} captioning deferred (vision disabled or unavailable); "
                                    + "extracted_text not cached so next read retries", entity.getId());
                        } else {
                            // Full extraction: cache to avoid re-extracting on subsequent calls.
                            updateExtractedText(entity.getId(), enrichment.text());
                        }
                        log.info("[Wiki] Extracted text from {}: {} chars (text) → {} chars (enriched), method={}, truncated={}, cached={}",
                                entity.getSourcePath(), text.length(), enrichment.text().length(),
                                json.getStr("method"), truncated, enrichment.shouldCache() && !truncated);
                        return enrichment.text();
                    }
                }
                log.warn("[Wiki] Document extraction returned no text for: {}", entity.getSourcePath());
            } catch (Exception e) {
                log.error("[Wiki] Document extraction failed for {}: {}", entity.getSourcePath(), e.getMessage());
            }
        }
        return entity.getOriginalContent();
    }

    /**
     * Enrichment outcome: text + a hint about whether the result is final.
     *
     * <p>{@code shouldCache=false} signals the caller to skip the
     * extracted_text cache write so the next call re-runs PDF image
     * captioning. This handles the common operator workflow of uploading
     * a PDF before flipping {@code wiki.ocr.enabled} on — without the
     * skip, the partial text-only result would block the eventual
     * caption catch-up.
     */
    private record EnrichmentOutcome(String text, boolean shouldCache) { }

    /**
     * For PDF raw materials, walks the inline images and appends a section of
     * {@code [图 P{n}#{m}]: <caption>} markers so downstream chunking and
     * search can index image contents.
     *
     * <p>Returns an {@link EnrichmentOutcome} that distinguishes three cases:
     * <ul>
     *   <li>Non-PDF / no extractor / no images present → text unchanged,
     *       {@code shouldCache=true}.</li>
     *   <li>Vision disabled but the PDF has inline images → text unchanged,
     *       {@code shouldCache=false} so the next read retries after the
     *       operator flips {@code wiki.ocr.enabled} on.</li>
     *   <li>Vision enabled and at least one image captioned → enriched text,
     *       {@code shouldCache=true}.</li>
     *   <li>Vision enabled but every image's caption call failed → text
     *       unchanged, {@code shouldCache=true} (transient failures should
     *       not block the cache; if the model is wedged, retry happens via
     *       a manual reprocess).</li>
     * </ul>
     */
    private EnrichmentOutcome appendPdfImageCaptions(WikiRawMaterialEntity entity, String body) {
        if (!"pdf".equals(entity.getSourceType()) || pdfImageExtractor == null) {
            return new EnrichmentOutcome(body, true);
        }

        java.nio.file.Path pdfPath = java.nio.file.Paths.get(entity.getSourcePath());

        // Vision-disabled path: only refuse the cache when the PDF actually has
        // qualifying images. Image-less PDFs can still be cached as text-only.
        boolean visionEnabled = featureFlagService != null
                && featureFlagService.isEnabled(VISION_FLAG_KEY);
        if (!visionEnabled) {
            boolean hasImages = pdfImageExtractor.hasInlineImages(pdfPath);
            if (hasImages) {
                log.info("[Wiki] PDF id={} has inline images but {} is disabled; "
                        + "skipping captioning and deferring extracted_text cache",
                        entity.getId(), VISION_FLAG_KEY);
                return new EnrichmentOutcome(body, false);
            }
            return new EnrichmentOutcome(body, true);
        }

        try {
            List<String> snippets = pdfImageExtractor.captionInlineImages(pdfPath);
            if (snippets.isEmpty()) {
                return new EnrichmentOutcome(body, true);
            }
            StringBuilder sb = new StringBuilder(body);
            sb.append("\n\n--- Inline images ---\n");
            for (String snippet : snippets) {
                sb.append(snippet).append('\n');
            }
            return new EnrichmentOutcome(sb.toString(), true);
        } catch (Exception e) {
            log.warn("[Wiki] PDF inline-image captioning failed for id={}: {}",
                    entity.getId(), e.getMessage());
            return new EnrichmentOutcome(body, true);
        }
    }

    /**
     * Routes an image-typed raw material through the vision-in pipeline,
     * caches the resulting caption into {@code extracted_text} so the next
     * call short-circuits, and degrades gracefully on failure.
     *
     * <p>Failure modes (feature flag off, no provider, all providers failed,
     * IO errors reading the image bytes) are intentionally swallowed and
     * surfaced as the empty string. The upload still succeeded; the raw
     * row remains and downstream code is expected to tolerate "no
     * extracted text yet" — calling this method again later (e.g. after
     * an operator enables the feature flag) re-runs the pipeline.
     */
    private String extractTextFromImage(WikiRawMaterialEntity entity) {
        if (entity.getSourcePath() == null || entity.getSourcePath().isBlank()) {
            log.warn("[Wiki] Image raw material missing sourcePath: id={}", entity.getId());
            return "";
        }
        byte[] imageBytes;
        try {
            imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(entity.getSourcePath()));
        } catch (Exception e) {
            log.error("[Wiki] Failed to read image bytes for id={}: {}", entity.getId(), e.getMessage());
            return "";
        }

        VisionRequest request = VisionRequest.builder()
                .imageBytes(imageBytes)
                .mimeType(resolveMimeType(entity))
                .build();
        VisionResult result;
        try {
            result = imageVisionService.caption(request);
        } catch (Exception e) {
            log.warn("[Wiki] Vision pipeline failed for raw id={}: {}", entity.getId(), e.getMessage());
            return "";
        }

        StringBuilder text = new StringBuilder(result.getCaption() == null ? "" : result.getCaption());
        if (result.getVisibleText() != null && !result.getVisibleText().isBlank()) {
            text.append("\n\n--- Visible text ---\n").append(result.getVisibleText());
        }
        String combined = text.toString();
        updateExtractedText(entity.getId(), combined);
        log.info("[Wiki] Image vision captioned raw id={} provider={} model={} chars={}",
                entity.getId(), result.getProviderId(), result.getModel(), combined.length());
        return combined;
    }

    /** Best-effort MIME resolution: prefer the persisted column, fall back to file extension. */
    private static String resolveMimeType(WikiRawMaterialEntity entity) {
        if (entity.getMimeType() != null && !entity.getMimeType().isBlank()) {
            return entity.getMimeType();
        }
        String path = entity.getSourcePath() == null ? "" : entity.getSourcePath().toLowerCase();
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tiff", "tif" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    /**
     * Recover raw materials stuck in 'processing' status after a server restart.
     * Resets them to 'pending', clears stale progress fields, and optionally
     * fires processing events so they get picked up automatically.
     *
     * @return number of recovered rows
     */
    @Transactional
    public int recoverStuckRawMaterialsOnStartup() {
        List<WikiRawMaterialEntity> stuck = rawMapper.selectList(
                new LambdaQueryWrapper<WikiRawMaterialEntity>()
                        .eq(WikiRawMaterialEntity::getProcessingStatus, "processing"));
        if (stuck.isEmpty()) return 0;

        for (WikiRawMaterialEntity raw : stuck) {
            raw.setProcessingStatus("pending");
            raw.setProgressPhase(null);
            raw.setProgressTotal(0);
            raw.setProgressDone(0);
            raw.setErrorMessage(null);
            rawMapper.updateById(raw);

            if (properties.isAutoProcessOnUpload()) {
                eventPublisher.publishEvent(new WikiProcessingEvent(this, raw.getId(), raw.getKbId()));
            }
            log.info("[Wiki] Recovered stuck processing raw material: id={}, kbId={}", raw.getId(), raw.getKbId());
        }
        return stuck.size();
    }

    /**
     * Handle a duplicate upload: decide what to do based on the existing row's status.
     * - completed → return as-is (no reprocessing needed)
     * - partial / failed → reprocess (partial enters resume branch)
     * - pending / processing → return as-is (already queued or running)
     */
    private WikiRawMaterialEntity handleDuplicate(WikiRawMaterialEntity existing) {
        String prevStatus = existing.getProcessingStatus();
        log.info("[Wiki] Duplicate file detected, reusing id={}, prevStatus={}", existing.getId(), prevStatus);

        if ("partial".equals(prevStatus) || "failed".equals(prevStatus)) {
            reprocess(existing.getId());
        }
        // completed / pending / processing → return as-is
        return existing;
    }

    /**
     * Delete a file from disk if it exists (cleanup for dedup-discarded uploads).
     */
    private void cleanupFile(String path) {
        if (path == null) return;
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path));
        } catch (Exception e) {
            log.warn("[Wiki] Failed to clean up duplicate upload file {}: {}", path, e.getMessage());
        }
    }

    private String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[Wiki] Failed to compute content hash: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SHA-256 over raw bytes. Used for file uploads so that PDF/Office binaries
     * produce a stable identity hash regardless of UTF-8 round-tripping.
     */
    private String computeHashOfBytes(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("[Wiki] Failed to compute byte hash: {}", e.getMessage());
            return null;
        }
    }
}
