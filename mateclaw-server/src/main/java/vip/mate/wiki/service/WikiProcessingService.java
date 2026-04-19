package vip.mate.wiki.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.sse.WikiProgressBus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wiki 处理服务
 * <p>
 * 核心管线：将原始材料通过 LLM 消化为结构化 Wiki 页面。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiProcessingService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiPageService pageService;
    private final WikiChunkService chunkService;
    private final WikiEmbeddingService embeddingService;
    private final WikiProperties properties;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final ObjectMapper objectMapper;
    private final WikiProgressBus progressBus;
    private final WikiCitationService citationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private vip.mate.wiki.job.WikiProcessingJobService wikiJobService;

    /** Parallel chunk / material processing executor (JDK 21 virtual threads) */
    public static final ExecutorService WIKI_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * RFC-012 M2 v2 UI v2：单 raw 的进度计数器，多个并行 chunk 的 {@code processChunkTwoPhase}
     * 共享同一份 atomic 计数，避免 6 个 chunk 各写各的 progress 字段时互相覆盖（导致 UI 永远 preparing）。
     * <p>
     * 生命周期：{@code processRawMaterial} 入口 put，try/finally 出口 remove。
     */
    private static final class ProgressCounter {
        final AtomicInteger total = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        /** Page-level 失败计数（chunk 内单页 create / merge 抛异常）。
         *  注：DuplicateKeyException 触发的 fallback-to-update 不算 failure，
         *  内容仍合入了同 slug page。仅 LLM 调用爆炸、JSON 解析失败、内容为空等真失败才递增。 */
        final AtomicInteger failed = new AtomicInteger(0);
        final AtomicBoolean phaseBStarted = new AtomicBoolean(false);
        /**
         * 跨 chunk slug 抢占表：canonical slug → 第一个声明该概念的实际 slug。
         * <p>
         * 解决 LLM 在并行 chunk 中给同一概念起不同 slug 拼写（按词分组 vs 按字分隔）的问题。
         * 使用 {@link ConcurrentHashMap#computeIfAbsent} 实现原子抢占：先到的 chunk 把自己的
         * slug 注册为 winner，后到的 chunk 看到 winner 后会把内容写入 winner 对应的 page。
         */
        final ConcurrentHashMap<String, String> slugClaims = new ConcurrentHashMap<>();
    }

    private final ConcurrentHashMap<Long, ProgressCounter> progressCounters = new ConcurrentHashMap<>();

    /**
     * 处理单个原始材料
     */
    public void processRawMaterial(Long rawId) {
        processRawMaterial(rawId, false);
    }

    /**
     * 处理单个原始材料（支持强制重跑）
     *
     * @param rawId 材料 ID
     * @param force 为 true 时忽略 content_hash 短路（RFC-012 Change 5），用于模型/提示词变更后的强制重跑
     */
    public void processRawMaterial(Long rawId, boolean force) {
        // RFC-012 follow-up #3：消费续传标志（reprocess() 把 partial 改回 pending 之前打的标）。
        // 必须在 claimForProcessing 之前读，因为 claim 会把状态再改一次。
        // flag 只在内存中，server 重启会丢 → 重启后仍按 pending 走正常流程（退化为全量重跑，
        // 功能不丢失只是性能回退）。
        boolean isPartialResume = rawService.consumePartialResumeFlag(rawId);

        // CAS 式抢占：防止并发重复处理
        if (!rawService.claimForProcessing(rawId)) {
            log.debug("[Wiki] Raw material {} already claimed or not pending, skipping", rawId);
            return;
        }

        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null) {
            log.warn("[Wiki] Raw material not found: {}", rawId);
            return;
        }

        // RFC-012 Change 5：若 content_hash 与上次成功处理时一致，直接短路
        if (!force
                && raw.getContentHash() != null
                && raw.getContentHash().equals(raw.getLastProcessedHash())) {
            rawService.updateProcessingStatus(rawId, "completed", "Skipped: content unchanged since last processing");
            log.info("[Wiki] Skip reprocessing raw={} (content unchanged, hash={})", rawId, raw.getContentHash());
            return;
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(raw.getKbId());
        if (kb == null) {
            log.warn("[Wiki] Knowledge base not found for raw material: kbId={}", raw.getKbId());
            return;
        }

        kbService.updateStatus(kb.getId(), "processing");

        // RFC-030 §9.1: create a processing job record before starting
        if (wikiJobService != null) {
            try {
                wikiJobService.createHeavyIngest(kb.getId(), rawId);
            } catch (Exception e) {
                log.warn("[Wiki] Failed to create heavy ingest job record for raw={}: {}", rawId, e.getMessage());
            }
        }

        // RFC-012 M2 v2 UI v2：为本次 raw 处理创建共享进度计数器（多 chunk 共享，避免 race）
        progressCounters.put(rawId, new ProgressCounter());
        rawService.updateProgress(rawId, "route", 0, 0); // UI 立即看到 indeterminate 滑条

        // RFC-012 M3：广播 raw.started（前端切到 indeterminate 进度条）
        progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_STARTED,
                java.util.Map.of("rawId", rawId, "phase", "route"));

        try {
            // Phase 1: 获取文本内容
            String textContent = rawService.getTextContent(raw);
            if (textContent == null || textContent.isBlank()) {
                rawService.updateProcessingStatus(rawId, "failed", "No text content available");
                kbService.updateStatus(kb.getId(), "active");
                return;
            }

            // Phase 2: 清除该材料之前生成的旧页面（仅独占+非手工页面）
            // RFC-012 follow-up #3：partial 状态走「续传」路径 —— 保留已生成的 page，让
            // route 阶段通过 existingPagesIndex 把它们归到 update 列表（phase B merge 覆盖
            // 当前 chunk 内容），失败的 slug 在 DB 里不存在，LLM 会放进 create 列表重跑。
            // isPartialResume 标记在 rawService.reprocess() 中写入 in-memory 集合，由
            // rawService.consumePartialResumeFlag() 在 claim 之前消费掉；无法通过
            // raw.getProcessingStatus() 判断是因为 claimForProcessing 已经把它改成 "processing"。
            if (isPartialResume) {
                log.info("[Wiki] Partial resume for raw={}: keeping existing pages, LLM will merge new content into them via existingPagesIndex", rawId);
            } else {
                int cleaned = pageService.deleteExclusiveBySourceRawId(kb.getId(), rawId);
                if (cleaned > 0) {
                    log.info("[Wiki] Cleaned {} exclusive old pages for raw material {} before reprocessing", cleaned, rawId);
                }
            }

            // Phase 3: 构建已有页面索引（一次构建，所有 chunk 共用）
            String existingPagesIndex = buildExistingPagesIndex(kb.getId());

            // Phase 3: LLM 消化
            // result[0] = totalPages, result[1] = failedChunks, result[2] = totalChunks
            int[] result;
            if (textContent.length() > properties.getMaxChunkSize()) {
                result = processInChunks(kb, raw, textContent, existingPagesIndex);
            } else {
                // 单 chunk 也持久化（RFC-013：保证所有 chunk 都入库）
                try {
                    chunkService.persistChunks(kb.getId(), rawId,
                            List.of(textContent), List.of(new int[]{0, textContent.length()}));
                } catch (Exception e) {
                    log.warn("[Wiki] Single chunk persistence failed for raw={}: {}", rawId, e.getMessage());
                }
                int pages = processChunk(kb, raw, textContent, existingPagesIndex);
                result = new int[]{pages, pages == 0 ? 1 : 0, 1};
            }

            int totalPages = result[0];
            int failedChunks = result[1];
            int totalChunks = result[2];

            // Phase 3: 更新状态和计数
            // 读 page 级失败数（finally 之前读，finally 才 remove counter）
            ProgressCounter pcFinal = progressCounters.get(rawId);
            int failedPages = pcFinal != null ? pcFinal.failed.get() : 0;

            String finalStatus;
            String finalDetail = null;
            if (totalPages == 0) {
                rawService.updateProcessingStatus(rawId, "failed", "No pages generated from LLM response");
                finalStatus = "failed";
                finalDetail = "No pages generated from LLM response";
            } else if (failedChunks > 0 || failedPages > 0) {
                // 部分成功：chunk 整体失败 或 chunk 内有 page 失败
                // （M2 v2 follow-up：page 级失败原本被计入 completed，现在正确归 partial）
                StringBuilder detail = new StringBuilder();
                if (failedChunks > 0) {
                    detail.append(failedChunks).append(" of ").append(totalChunks).append(" chunks failed");
                }
                if (failedPages > 0) {
                    if (detail.length() > 0) detail.append("; ");
                    detail.append(failedPages).append(" page(s) failed");
                }
                detail.append(", ").append(totalPages).append(" pages generated");
                finalDetail = detail.toString();
                rawService.updateProcessingStatus(rawId, "partial", finalDetail);
                finalStatus = "partial";
                // 【Review Bug 1】partial 不写 lastProcessedHash：partial 的语义就是"还有失败、需要再跑"，
                // 写了会导致下次用户点"重新处理"被 hash 短路直接跳过，永远没机会修失败的 chunk。
            } else {
                rawService.updateProcessingStatus(rawId, "completed", null);
                finalStatus = "completed";
                // RFC-012 Change 5：记录本次成功处理时的 hash，供下次短路判断
                if (raw.getContentHash() != null) {
                    rawService.setLastProcessedHash(rawId, raw.getContentHash());
                }
            }
            int pageCount = pageService.countByKbId(kb.getId());
            kbService.setPageCount(kb.getId(), pageCount);
            kbService.updateStatus(kb.getId(), "active");

            // RFC-012 M3：广播终态
            if ("failed".equals(finalStatus)) {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_FAILED,
                        java.util.Map.of("rawId", rawId, "error", finalDetail == null ? "" : finalDetail));
            } else {
                progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_COMPLETED,
                        java.util.Map.of(
                                "rawId", rawId,
                                "status", finalStatus,
                                "totalPages", totalPages,
                                "kbPageCount", pageCount));
            }

            log.info("[Wiki] Processing completed for raw={}, kbId={}, generatedPages={}, totalPages={}",
                    rawId, kb.getId(), totalPages, pageCount);

            // RFC-011：异步嵌入新 chunk（不阻塞处理管线）
            // 注意：此方法目前未加 @Transactional，每个 DB 操作短事务独立提交。
            // 如果未来加了事务包裹 processRawMaterial，这里的异步任务需要改用
            // TransactionSynchronizationManager.registerSynchronization(afterCommit)
            // 否则新线程会查不到 chunk（事务未提交）导致 embedding 静默跳过。
            if (totalPages > 0) {
                final Long fKbId = kb.getId();
                WIKI_EXECUTOR.submit(() -> {
                    try {
                        int embedded = embeddingService.embedMissingChunks(fKbId);
                        if (embedded > 0) {
                            log.info("[Wiki] Async embedding completed: kbId={}, embedded={}", fKbId, embedded);
                        }
                    } catch (Exception ex) {
                        log.warn("[Wiki] Async embedding failed for kbId={}: {}", fKbId, ex.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            log.error("[Wiki] Processing failed for raw={}: {}", rawId, e.getMessage(), e);
            rawService.updateProcessingStatus(rawId, "failed", e.getMessage());
            kbService.updateStatus(kb.getId(), "active");
            // RFC-012 M3：广播异常终态
            progressBus.broadcast(kb.getId(), WikiProgressBus.EVENT_RAW_FAILED,
                    java.util.Map.of("rawId", rawId, "error", e.getMessage() == null ? "unknown" : e.getMessage()));
        } finally {
            // RFC-012 M2 v2 UI v2：写入最终进度并清理共享计数器
            ProgressCounter pc = progressCounters.remove(rawId);
            if (pc != null) {
                rawService.updateProgress(rawId, "done", pc.done.get(), pc.total.get());
            }
        }
    }

    /**
     * 处理知识库中所有待处理的原始材料
     * <p>
     * RFC-012 Change 1：材料级并行，受 {@link WikiProperties#getMaxParallelRawMaterials()} 约束。
     */
    public void processAllPending(Long kbId) {
        List<WikiRawMaterialEntity> pendingList = rawService.listPending(kbId);
        if (pendingList.isEmpty()) {
            log.info("[Wiki] No pending raw materials for kbId={}", kbId);
            return;
        }
        int parallel = Math.max(1, properties.getMaxParallelRawMaterials());
        log.info("[Wiki] Processing {} pending raw materials for kbId={} with parallelism={}",
                pendingList.size(), kbId, parallel);

        Semaphore rawSem = new Semaphore(parallel);
        List<CompletableFuture<Void>> futures = new ArrayList<>(pendingList.size());
        for (WikiRawMaterialEntity raw : pendingList) {
            final Long rawId = raw.getId();
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    rawSem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    processRawMaterial(rawId);
                } finally {
                    rawSem.release();
                }
            }, WIKI_EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 分块处理大文档（并行执行，Semaphore 控制并发）
     *
     * @return int[3]: [totalPages, failedChunks, totalChunks]
     */
    private int[] processInChunks(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw, String text,
                                      String existingPagesIndex) {
        // Phase 1: 切分文本为 chunks（带偏移，供持久化）
        List<ChunkWithOffset> chunksWithOffset = splitIntoChunksWithOffsets(text);
        List<String> chunks = chunksWithOffset.stream().map(ChunkWithOffset::text).toList();
        int totalChunks = chunks.size();
        log.info("[Wiki] Split into {} chunks for raw={}, kbId={}", totalChunks, raw.getId(), kb.getId());

        // RFC-013：持久化 chunk 到 mate_wiki_chunk（增量对账：hash 不变的保留）
        try {
            List<int[]> offsets = chunksWithOffset.stream()
                    .map(c -> new int[]{c.startOffset(), c.endOffset()}).toList();
            chunkService.persistChunks(kb.getId(), raw.getId(), chunks, offsets);
        } catch (Exception e) {
            log.warn("[Wiki] Chunk persistence failed for raw={}, continuing without: {}", raw.getId(), e.getMessage());
        }

        if (totalChunks == 1) {
            // 单 chunk 不走并行
            try {
                int pages = processChunk(kb, raw, chunks.get(0), existingPagesIndex);
                return new int[]{pages, pages == 0 ? 1 : 0, 1};
            } catch (Exception e) {
                log.warn("[Wiki] Single chunk failed: {}", e.getMessage());
                return new int[]{0, 1, 1};
            }
        }

        // Phase 2: 并行处理（Semaphore 限制并发数）
        int parallelChunks = Math.max(1, properties.getMaxParallelChunks());
        Semaphore semaphore = new Semaphore(parallelChunks);
        AtomicInteger totalPages = new AtomicInteger(0);
        AtomicInteger failedChunks = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            final int chunkIndex = i;
            final String chunk = chunks.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedChunks.incrementAndGet();
                    return;
                }
                try {
                    log.info("[Wiki] Processing chunk {}/{}: {} chars", chunkIndex + 1, totalChunks, chunk.length());
                    int pages = processChunk(kb, raw, chunk, existingPagesIndex);
                    totalPages.addAndGet(pages);
                } catch (Exception e) {
                    failedChunks.incrementAndGet();
                    if (e.getMessage() != null && e.getMessage().contains("content_filter")) {
                        log.warn("[Wiki] Chunk {}/{} blocked by content filter", chunkIndex + 1, totalChunks);
                    } else {
                        log.warn("[Wiki] Chunk {}/{} failed: {}", chunkIndex + 1, totalChunks, e.getMessage());
                    }
                } finally {
                    semaphore.release();
                }
            }, WIKI_EXECUTOR));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return new int[]{totalPages.get(), failedChunks.get(), totalChunks};
    }

    /**
     * 将文本切分为多个 chunks（智能句子边界，支持中英文）
     */
    private List<String> splitIntoChunks(String text) {
        return splitIntoChunksWithOffsets(text).stream().map(ChunkWithOffset::text).toList();
    }

    /** chunk 文本 + 在原始文本中的偏移 */
    record ChunkWithOffset(String text, int startOffset, int endOffset) {}

    /**
     * 切分并记录每个 chunk 的原始偏移（RFC-013：供 WikiChunkService 持久化）
     */
    private List<ChunkWithOffset> splitIntoChunksWithOffsets(String text) {
        int chunkSize = properties.getMaxChunkSize();
        int overlap = Math.min(500, chunkSize / 10);
        List<ChunkWithOffset> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 在句子边界切分（支持中英文）
            if (end < text.length()) {
                int breakAt = findSentenceBoundary(text, start, end, chunkSize);
                if (breakAt > start) {
                    end = breakAt;
                }
            }

            chunks.add(new ChunkWithOffset(text.substring(start, end), start, end));

            // 前进（带 overlap 防止边界上下文丢失）
            int nextStart = end - overlap;
            if (nextStart <= start) nextStart = end; // 防止死循环
            start = nextStart;
        }
        return chunks;
    }

    /**
     * 在指定范围内找句子边界（优先级：段落 > 中文句号 > 英文句号 > 换行 > 空格）
     */
    private int findSentenceBoundary(String text, int start, int end, int chunkSize) {
        int halfChunk = start + chunkSize / 2;

        // 优先：段落分隔（双换行）
        int lastPara = text.lastIndexOf("\n\n", end);
        if (lastPara > halfChunk) return lastPara + 2;

        // 中文句号
        int lastChinese = text.lastIndexOf("。", end);
        if (lastChinese > halfChunk) return lastChinese + 1;

        // 英文句号（后面跟空格或换行，排除缩写如 "Dr." "e.g."）
        for (int i = end - 1; i > halfChunk; i--) {
            if (text.charAt(i) == '.' && i + 1 < text.length()
                    && (text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\n')
                    && i > 0 && Character.isLowerCase(text.charAt(i - 1))) {
                return i + 1;
            }
        }

        // 换行
        int lastNewline = text.lastIndexOf("\n", end);
        if (lastNewline > halfChunk) return lastNewline + 1;

        // 空格（word boundary）
        int lastSpace = text.lastIndexOf(" ", end);
        if (lastSpace > halfChunk) return lastSpace + 1;

        return end; // 无合适边界，硬切
    }

    /**
     * 处理单个文本块
     *
     * @return 创建+更新的页面数
     */
    private int processChunk(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw, String textContent,
                               String existingPagesIndex) {
        // RFC-012 M2：两阶段消化（路由 → 逐页 merge），单次 LLM 调用输出量大幅缩减，避免 nginx 60s 网关超时
        if (properties.isUseTwoPhaseDigest()) {
            return processChunkTwoPhase(kb, raw, textContent, existingPagesIndex);
        }

        // 旧路径：单次调用让 LLM 同时处理新建 + 全量 merge（输出爆炸，易触发 504）
        String systemPrompt = PromptLoader.loadPrompt("wiki/digest-system");
        String userTemplate = PromptLoader.loadPrompt("wiki/digest-user");

        String userPrompt = userTemplate
                .replace("{config}", kb.getConfigContent() != null ? kb.getConfigContent() : "")
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", textContent);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ));
        String llmResponse = callLlmWithResilientRetry(prompt, "chunk of raw=" + raw.getId());

        return applyLlmResponse(kb.getId(), raw.getId(), llmResponse);
    }

    /**
     * RFC-012 M2 两阶段消化：
     * <p>
     * 阶段 A（route）：一次 LLM 调用决定要 create 哪些新页 + 要 update 哪些已有页（仅 slug 列表）。
     * 输入小、输出短，单次稳定在 30s 内返回。
     * <p>
     * 阶段 B（merge）：对 update 列表里的每个 slug 单独发 LLM 调用，输入只塞这一页的现有正文 + 当前
     * chunk 文本，输出该页 merge 后的完整内容。每次调用单页规模，远不会触发 nginx 60s 超时。
     * <p>
     * 新建页直接落库；merge 页因互不依赖，可在当前 chunk 的 virtual thread 内顺序处理（chunk 之间
     * 已通过 maxParallelChunks Semaphore 拿到了并行度）。
     *
     * @return 创建+更新的页面数
     */
    private int processChunkTwoPhase(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                       String textContent, String existingPagesIndex) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String rawTitle = raw.getTitle();

        // RFC-012 M2 v2 UI v2：取共享进度计数器（processRawMaterial 入口已 put）。
        // 多 chunk 并行时所有 chunk 共享同一份 atomic 计数，避免互相覆盖把 UI 拉回 preparing。
        ProgressCounter pc = progressCounters.get(rawId);

        // ─── 阶段 A：路由 ───
        String routeSystem = PromptLoader.loadPrompt("wiki/route-system");
        String routeUserTemplate = PromptLoader.loadPrompt("wiki/route-user");
        String routeUser = routeUserTemplate
                .replace("{config}", configContent)
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{raw_title}", rawTitle)
                .replace("{raw_content}", textContent);
        Prompt routePrompt = new Prompt(List.of(
                new SystemMessage(routeSystem),
                new UserMessage(routeUser)
        ));
        String routeResponse = callLlmWithResilientRetry(routePrompt, "route chunk of raw=" + rawId);
        JsonNode routeJson = parseJsonResponse(routeResponse);
        if (routeJson == null) {
            log.warn("[Wiki] Route phase: failed to parse JSON for kbId={}, rawId={}, responseLen={}, first200={}",
                    kbId, rawId, routeResponse != null ? routeResponse.length() : 0,
                    routeResponse != null ? routeResponse.substring(0, Math.min(200, routeResponse.length())) : "null");
            return 0;
        }

        // RFC-012 follow-up #3：phase B 现在并行执行，计数必须是 atomic
        AtomicInteger created = new AtomicInteger(0);
        AtomicInteger updated = new AtomicInteger(0);

        // ─── 收集 route 输出（仅 metadata，无 content） ───
        List<JsonNode> createMetas = new ArrayList<>();
        JsonNode createNode = routeJson.path("create");
        if (createNode.isArray()) {
            for (JsonNode metaNode : createNode) {
                String slug = metaNode.path("slug").asText("");
                String title = metaNode.path("title").asText("");
                if (slug.isBlank() || title.isBlank()) continue;
                createMetas.add(metaNode);
            }
        }
        List<String> updateSlugs = new ArrayList<>();
        JsonNode updateNode = routeJson.path("update");
        if (updateNode.isArray()) {
            for (JsonNode slugNode : updateNode) {
                String slug = slugNode.asText("");
                if (!slug.isBlank()) updateSlugs.add(slug);
            }
        }
        int totalPlanned = createMetas.size() + updateSlugs.size();
        log.info("[Wiki] Route phase: kbId={}, rawId={}, planned create={}, planned update={}",
                kbId, rawId, createMetas.size(), updateSlugs.size());

        // RFC-012 M2 v2 UI v2：把本 chunk 的计划数累加到共享 total；切换到 phase-b（仅首次切换需 log）
        if (pc != null) {
            pc.total.addAndGet(totalPlanned);
            if (pc.phaseBStarted.compareAndSet(false, true)) {
                log.info("[Wiki] Progress: switching to phase-b for raw={}", rawId);
                // RFC-012 M3：route 完成、phase-b 启动 → 通知前端确定进度（可显示 0/N）
                progressBus.broadcast(kbId, WikiProgressBus.EVENT_ROUTE_DONE,
                        java.util.Map.of(
                                "rawId", rawId,
                                "phase", "phase-b",
                                "done", pc.done.get(),
                                "total", pc.total.get()));
            }
            rawService.updateProgress(rawId, "phase-b", pc.done.get(), pc.total.get());
        }

        // RFC-012 follow-up #3：阶段 B 页级并发。每个 page 是独立的 LLM 调用，相互无依赖，
        // 串行跑会让一个卡超时的 page 阻塞整个 chunk。受 maxParallelPhaseBPages Semaphore 约束，
        // 复用虚拟线程池 WIKI_EXECUTOR。
        int parallelPages = Math.max(1, properties.getMaxParallelPhaseBPages());
        Semaphore pageSem = new Semaphore(parallelPages);

        // ─── 阶段 B-1：并行 create ───
        List<CompletableFuture<Void>> createFutures = new ArrayList<>(createMetas.size());
        for (JsonNode meta : createMetas) {
            final JsonNode metaRef = meta;
            createFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    pageSem.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean ok = false;
                try {
                    try {
                        if (createOnePage(kb, raw, textContent, existingPagesIndex, metaRef)) {
                            created.incrementAndGet();
                        }
                        // createOnePage 内部的 DuplicateKey / canonical / claim fallback 不抛异常 → ok=true。
                        ok = true;
                    } catch (RuntimeException e) {
                        log.warn("[Wiki] Phase B create page slug='{}' failed: {}",
                                metaRef.path("slug").asText(""), e.getMessage());
                    }
                    if (pc != null) {
                        int d = pc.done.incrementAndGet();
                        if (!ok) pc.failed.incrementAndGet();
                        rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                        progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                                java.util.Map.of(
                                        "rawId", rawId,
                                        "kind", "create",
                                        "ok", ok,
                                        "done", d,
                                        "total", pc.total.get()));
                    }
                } finally {
                    pageSem.release();
                }
            }, WIKI_EXECUTOR));
        }

        // ─── 阶段 B-2：并行 merge ───
        List<CompletableFuture<Void>> mergeFutures = new ArrayList<>(updateSlugs.size());
        for (String slug : updateSlugs) {
            final String mergeSlug = slug;
            mergeFutures.add(CompletableFuture.runAsync(() -> {
                try {
                    pageSem.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean ok = false;
                try {
                    try {
                        if (mergeOnePage(kb, raw, textContent, mergeSlug)) {
                            updated.incrementAndGet();
                        }
                        ok = true;
                    } catch (RuntimeException e) {
                        log.warn("[Wiki] Phase B merge page slug='{}' failed: {}", mergeSlug, e.getMessage());
                    }
                    if (pc != null) {
                        int d = pc.done.incrementAndGet();
                        if (!ok) pc.failed.incrementAndGet();
                        rawService.updateProgress(rawId, "phase-b", d, pc.total.get());
                        progressBus.broadcast(kbId, WikiProgressBus.EVENT_CHUNK_DONE,
                                java.util.Map.of(
                                        "rawId", rawId,
                                        "kind", "merge",
                                        "ok", ok,
                                        "done", d,
                                        "total", pc.total.get()));
                    }
                } finally {
                    pageSem.release();
                }
            }, WIKI_EXECUTOR));
        }

        // 等待本 chunk 的 create + merge 全部完成
        List<CompletableFuture<Void>> allFutures = new ArrayList<>(createFutures.size() + mergeFutures.size());
        allFutures.addAll(createFutures);
        allFutures.addAll(mergeFutures);
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        // 单 chunk 完成时不写"done"——多 chunk 还在跑；最终"done"由 processRawMaterial 的 finally 写入

        log.info("[Wiki] Two-phase digest applied: kbId={}, rawId={}, created={}, updated={}",
                kbId, rawId, created.get(), updated.get());
        return created.get() + updated.get();
    }

    /**
     * RFC-012 M2 v2 — 阶段 B 单页生成：用 chunk 文本 + 该页 metadata 让 LLM 写出完整页面。
     * <p>
     * 输入仅几 KB（chunk 主题片段 + metadata + 已有页索引），输出仅一页 markdown，
     * 单次调用稳稳 ≤ 60 秒，避免 nginx 60s 网关。
     * <p>
     * 兜底：如果 slug 已存在（route 误判），改走 update 路径。
     *
     * @return true 表示成功 create 一页（或兜底 update 一页时返回 false 以让上层归到 update 计数）
     */
    private boolean createOnePage(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                    String chunkText, String existingPagesIndex, JsonNode meta) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        String slug = meta.path("slug").asText("");
        String title = meta.path("title").asText("");
        String summary = meta.path("summary").asText("");
        if (slug.isBlank() || title.isBlank()) return false;

        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String createSystem = PromptLoader.loadPrompt("wiki/create-page-system");
        String createUserTemplate = PromptLoader.loadPrompt("wiki/create-page-user");
        String createUser = createUserTemplate
                .replace("{config}", configContent)
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{page_slug}", slug)
                .replace("{page_title}", title)
                .replace("{page_summary}", summary)
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", chunkText);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(createSystem),
                new UserMessage(createUser)
        ));
        String response = callLlmWithResilientRetry(prompt,
                "create page slug=" + slug + " of raw=" + rawId);
        JsonNode pageJson = parseJsonResponse(response);
        if (pageJson == null) {
            log.warn("[Wiki] Phase B create page slug='{}' returned unparseable JSON, skipping", slug);
            return false;
        }
        String content = pageJson.path("content").asText("");
        String pageSummary = pageJson.path("summary").asText("");
        if (pageSummary.isBlank()) pageSummary = summary;
        if (content.isBlank()) {
            log.warn("[Wiki] Phase B create page slug='{}' returned blank content, skipping", slug);
            return false;
        }

        // 兜底 0：跨拼写 canonical 匹配（DB 已有 page，但 slug 拼写不同）
        //   覆盖场景：之前上传的 raw 已经创建了同概念 page，本次 LLM 给了不同拼写
        WikiPageEntity existingByCanonical = pageService.findByCanonicalSlug(kbId, slug);
        if (existingByCanonical != null && !existingByCanonical.getSlug().equals(slug)) {
            String actualSlug = existingByCanonical.getSlug();
            pageService.updatePageByAi(kbId, actualSlug, content, pageSummary, rawId);
            log.info("[Wiki] Phase B create slug='{}' canonical-matches existing '{}', updated",
                    slug, actualSlug);
            return false;
        }

        // 兜底 0.5：跨 chunk in-flight slug 抢占（同一 raw 的另一并发 chunk 已声明同概念）
        //   computeIfAbsent 是原子操作，先到的 chunk 把自己 slug 注册为 winner
        ProgressCounter pcLocal = progressCounters.get(rawId);
        String canonical = WikiPageService.canonicalSlug(slug);
        if (pcLocal != null && !canonical.isEmpty()) {
            // lambda 要求 effectively final，用 finalSlug 副本
            final String routedSlug = slug;
            String winnerSlug = pcLocal.slugClaims.computeIfAbsent(canonical, k -> routedSlug);
            if (!winnerSlug.equals(slug)) {
                // 另一 chunk 先 claim 了同 canonical，但用了不同 slug 拼写
                WikiPageEntity winner = pageService.getBySlug(kbId, winnerSlug);
                if (winner != null) {
                    // winner 已 INSERT 进 DB → 直接 update
                    pageService.updatePageByAi(kbId, winnerSlug, content, pageSummary, rawId);
                    log.info("[Wiki] Phase B create slug='{}' lost slug-claim race to '{}', updated",
                            slug, winnerSlug);
                    return false;
                }
                // winner claim 早于 INSERT（claim 是 in-memory，INSERT 是 DB IO）
                // → 用 winnerSlug 继续走下面的 INSERT 路径，DuplicateKey fallback 会兜住实际 race
                log.info("[Wiki] Phase B create slug='{}' redirects to in-flight winner '{}'",
                        slug, winnerSlug);
                slug = winnerSlug;
            }
        }

        // 兜底 1：如果 slug 已存在（route 误判 / 上一次成功 INSERT），走 update 而不是 create
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing != null) {
            pageService.updatePageByAi(kbId, slug, content, pageSummary, rawId);
            log.info("[Wiki] Phase B create page slug='{}' done (updated existing)", slug);
            return false; // 不计入 created
        }
        String sourceRawIds = "[" + rawId + "]";
        try {
            WikiPageEntity created = pageService.createPage(kbId, slug, title, content, pageSummary, sourceRawIds);
            log.info("[Wiki] Phase B create page slug='{}' done (created)", slug);
            // RFC-029: async citation build
            citationService.buildCitationsAsync(created.getId(), kbId);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 兜底 2：select-then-create 在并发下不是原子操作。当 N 个 chunk 同时
            // route 出相同 slug，只有第一个 INSERT 能成功，其余都会触发 H2/MySQL
            // unique key violation。本次 chunk 的 LLM 输出仍有价值——降级为 update，
            // 把内容合并进已存在的 page，而不是丢弃。
            pageService.updatePageByAi(kbId, slug, content, pageSummary, rawId);
            log.info("[Wiki] Phase B create page slug='{}' lost INSERT race -> updated existing", slug);
            return false; // 不计入 created
        }
    }

    /**
     * RFC-012 M2 v2 — 阶段 B 单页 merge：把 chunk 文本合并进一个已有页面。
     * <p>
     * 输入仅几 KB（该页现有 content + chunk 主题片段），输出仅一页 markdown。
     *
     * @return true 表示成功 update 一页
     */
    private boolean mergeOnePage(WikiKnowledgeBaseEntity kb, WikiRawMaterialEntity raw,
                                   String chunkText, String slug) {
        Long kbId = kb.getId();
        Long rawId = raw.getId();
        WikiPageEntity existing = pageService.getBySlug(kbId, slug);
        if (existing == null) {
            // 兜底：跨拼写 canonical 匹配——LLM 给的 slug 在 DB 里找不到，
            // 但 canonical 形式（去连字符）对得上某个已有 page（典型场景：
            // route 输出 `zhong-yao-qi-qing-pei-wu`，DB 存 `zhongyao-qiqing-peiwu`）
            existing = pageService.findByCanonicalSlug(kbId, slug);
            if (existing != null && !existing.getSlug().equals(slug)) {
                log.info("[Wiki] Phase B merge slug='{}' canonical-matches existing '{}', using canonical slug for LLM call",
                        slug, existing.getSlug());
                slug = existing.getSlug();
            } else {
                log.warn("[Wiki] Phase B merge page slug='{}' planned for update but not found in DB (even by canonical), skipping", slug);
                return false;
            }
        }

        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String mergeSystem = PromptLoader.loadPrompt("wiki/merge-page-system");
        String mergeUserTemplate = PromptLoader.loadPrompt("wiki/merge-page-user");
        String mergeUser = mergeUserTemplate
                .replace("{config}", configContent)
                .replace("{page_slug}", existing.getSlug() != null ? existing.getSlug() : slug)
                .replace("{page_title}", existing.getTitle() != null ? existing.getTitle() : "")
                .replace("{page_last_updated_by}", existing.getLastUpdatedBy() != null ? existing.getLastUpdatedBy() : "ai")
                .replace("{page_content}", existing.getContent() != null ? existing.getContent() : "")
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", chunkText);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(mergeSystem),
                new UserMessage(mergeUser)
        ));
        String response = callLlmWithResilientRetry(prompt,
                "merge page slug=" + slug + " of raw=" + rawId);
        JsonNode mergeJson = parseJsonResponse(response);
        if (mergeJson == null) {
            log.warn("[Wiki] Phase B merge page slug='{}' returned unparseable JSON, skipping", slug);
            return false;
        }
        String content = mergeJson.path("content").asText("");
        String summary = mergeJson.path("summary").asText("");
        if (content.isBlank()) {
            log.warn("[Wiki] Phase B merge page slug='{}' returned blank content, skipping", slug);
            return false;
        }
        WikiPageEntity updated = pageService.updatePageByAi(kbId, slug, content, summary, rawId);
        log.info("[Wiki] Phase B merge page slug='{}' done", slug);
        // RFC-029: async citation rebuild
        if (updated != null) {
            citationService.buildCitationsAsync(updated.getId(), kbId);
        }
        return true;
    }

    /**
     * 解析 LLM 响应并创建/更新 Wiki 页面
     *
     * @return 创建+更新的页面总数
     */
    private int applyLlmResponse(Long kbId, Long rawId, String llmResponse) {
        JsonNode root = parseJsonResponse(llmResponse);
        if (root == null) {
            log.warn("[Wiki] Failed to parse LLM response for kbId={}, rawId={}, responseLen={}, first200={}",
                    kbId, rawId, llmResponse != null ? llmResponse.length() : 0,
                    llmResponse != null ? llmResponse.substring(0, Math.min(200, llmResponse.length())) : "null");
            return 0;
        }

        // 结构校验：必须有 pages 数组
        if (!root.has("pages") || !root.get("pages").isArray()) {
            log.warn("[Wiki] LLM response missing 'pages' array for kbId={}, rawId={}", kbId, rawId);
            return 0;
        }

        String sourceRawIds = "[" + rawId + "]";
        int created = 0;
        int updated = 0;

        // 新页面
        JsonNode pagesNode = root.path("pages");
        if (pagesNode.isArray()) {
            for (JsonNode pageNode : pagesNode) {
                String slug = pageNode.path("slug").asText("");
                String title = pageNode.path("title").asText("");
                String content = pageNode.path("content").asText("");
                String summary = pageNode.path("summary").asText("");

                if (slug.isBlank() || title.isBlank()) continue;

                // 检查是否已存在（LLM 可能将已有页面误判为新页面）
                WikiPageEntity existing = pageService.getBySlug(kbId, slug);
                if (existing != null) {
                    pageService.updatePageByAi(kbId, slug, content, summary, rawId);
                    updated++;
                } else {
                    pageService.createPage(kbId, slug, title, content, summary, sourceRawIds);
                    created++;
                }
            }
        }

        // 更新的页面
        JsonNode updatedPagesNode = root.path("updated_pages");
        if (updatedPagesNode.isArray()) {
            for (JsonNode pageNode : updatedPagesNode) {
                String slug = pageNode.path("slug").asText("");
                String content = pageNode.path("content").asText("");
                String summary = pageNode.path("summary").asText("");

                if (slug.isBlank()) continue;

                WikiPageEntity existing = pageService.getBySlug(kbId, slug);
                if (existing != null) {
                    // 保护手动编辑的页面：仍然更新，但 LLM 已在 prompt 中被告知要保留手动内容
                    pageService.updatePageByAi(kbId, slug, content, summary, rawId);
                    updated++;
                }
            }
        }

        log.info("[Wiki] Applied LLM response: kbId={}, rawId={}, created={}, updated={}",
                kbId, rawId, created, updated);
        return created + updated;
    }

    /**
     * 构建已有 Wiki 页面索引（供 LLM 参考）
     */
    private String buildExistingPagesIndex(Long kbId) {
        List<WikiPageEntity> summaries = pageService.listSummaries(kbId);
        if (summaries.isEmpty()) {
            return "（暂无已有页面）";
        }

        StringBuilder sb = new StringBuilder();
        for (WikiPageEntity page : summaries) {
            sb.append("- **[[").append(page.getTitle()).append("]]** (slug: `").append(page.getSlug()).append("`");
            if ("manual".equals(page.getLastUpdatedBy())) {
                sb.append(", 手动编辑");
            }
            sb.append("): ");
            sb.append(page.getSummary() != null ? page.getSummary() : "无摘要");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * RFC-012 follow-up #3：Wiki 调用自带重试层（{@link #callLlmWithResilientRetry}），
     * 所以用 maxAttempts=1 的 RetryTemplate 关掉 Spring AI 的内层重试，避免两层重试互相抵消
     * （内层默认 2-3 次 × 180s readTimeout = 一次"外层 attempt"消耗 360-540s，
     * 让 wiki 的 maxTotalDurationMs=240s 被穿越，maxAttempts=5 永远到不了）。
     */
    private static final RetryTemplate WIKI_NO_RETRY = RetryTemplate.builder()
            .maxAttempts(1)
            .build();

    private ChatModel buildChatModel() {
        ModelConfigEntity defaultModel = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(defaultModel, WIKI_NO_RETRY);
    }

    /**
     * 调用 LLM，带"任务完成或模型不可用才终止"的重试策略。
     * <p>
     * 可重试（一直重试直到成功）：网络抖动、5xx、429 限流、超时、连接中断、内容过滤偶发、JSON 空输出。
     * <p>
     * 立即终止（模型不可用）：401/403 认证失败、模型不存在、quota 用尽、非法 API key、
     * InterruptedException（优雅关停）。
     * <p>
     * 使用指数退避（1s → 2s → 4s → ... → 封顶 60s）。
     * <p>
     * RFC-012 M1：加入 maxAttempts 与 maxTotalDurationMs 双重上限，避免 nginx 504 这种
     * 反复瞬时错误把单 chunk 卡到永远；buildChatModel 提到循环外，所有重试复用同一实例。
     */
    private String callLlmWithResilientRetry(Prompt prompt, String ctx) {
        long backoffMs = 1000;
        final long maxBackoffMs = 60_000;
        final int maxAttempts = Math.max(1, properties.getLlmMaxAttempts());
        final long maxTotalDurationMs = Math.max(1_000L, properties.getLlmMaxTotalDurationMs());
        final long startNanos = System.nanoTime();
        final ChatModel chatModel = buildChatModel();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                ChatResponse response = chatModel.call(prompt);
                if (response == null || response.getResult() == null
                        || response.getResult().getOutput() == null
                        || response.getResult().getOutput().getText() == null
                        || response.getResult().getOutput().getText().isBlank()) {
                    throw new TransientLlmException("Empty response from model");
                }
                if (attempt > 1) {
                    log.info("[Wiki] LLM call for {} succeeded on attempt {}", ctx, attempt);
                }
                return response.getResult().getOutput().getText();
            } catch (Throwable t) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("LLM call interrupted for " + ctx, t);
                }
                String rootInfo = summarizeRoot(t);
                if (isFatalModelError(t)) {
                    log.error("[Wiki] LLM unavailable (fatal) for {} after {} attempts (rootCause={}): {}",
                            ctx, attempt, rootInfo, t.getMessage());
                    throw new RuntimeException("LLM unavailable (rootCause=" + rootInfo + "): " + t.getMessage(), t);
                }
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (attempt >= maxAttempts || elapsedMs >= maxTotalDurationMs) {
                    log.error("[Wiki] LLM exhausted for {} after {} attempts in {}ms (limits: maxAttempts={}, maxTotalDurationMs={}, rootCause={}): {}",
                            ctx, attempt, elapsedMs, maxAttempts, maxTotalDurationMs, rootInfo, t.getMessage());
                    throw new RuntimeException("LLM exhausted after " + attempt + " attempts in " + elapsedMs
                            + "ms (rootCause=" + rootInfo + "): " + t.getMessage(), t);
                }
                long sleepMs = Math.min(backoffMs, Math.max(0L, maxTotalDurationMs - elapsedMs));
                log.warn("[Wiki] LLM transient failure for {} attempt={}/{} elapsed={}ms, retrying in {}ms (rootCause={}): {}",
                        ctx, attempt, maxAttempts, elapsedMs, sleepMs, rootInfo, t.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM retry interrupted for " + ctx, ie);
                }
                backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
            }
        }
    }

    /**
     * 判断是否为"模型不可用"级别的致命错误（不重试，立即终止）。
     * <p>
     * 三类视为 fatal：
     * <ul>
     *   <li><b>鉴权 / 配额 / 模型不存在</b>：401/403、invalid api key、model not found、quota 用尽</li>
     *   <li><b>prompt 结构性错误</b>：上下文超长、max_tokens 限制、prompt too long（重试也得同样结果）</li>
     *   <li><b>内容审核过滤</b>：content_filter 触发（被 safety 挡下的 prompt 重试也是同样结果）</li>
     * </ul>
     * 其余（网络、超时、5xx、429 限流、偶发空响应）均视为瞬时，按指数退避持续重试。
     * <p>
     * 说明：关键字启发式在极少数场景可能误判（例如瞬时错误的 message 恰好含 "authentication"），
     * 但实际云厂商 SDK 的错误消息规范度较高，这个风险可接受。
     */
    private boolean isFatalModelError(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            // 按异常类型直接判 fatal —— DNS / 连接拒绝 / TLS 问题重试都是浪费
            String className = cur.getClass().getSimpleName();
            if ("UnknownHostException".equals(className)
                    || "SSLHandshakeException".equals(className)
                    || "CertificateException".equals(className)
                    || "SSLPeerUnverifiedException".equals(className)) {
                return true;
            }
            if ("ConnectException".equals(className) && cur.getMessage() != null
                    && cur.getMessage().toLowerCase().contains("refused")) {
                return true;
            }

            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                // 鉴权 / 配额 / 模型不存在（HTTP 401/403 + 提供方错误字段）
                if (m.contains("401") || m.contains("unauthorized")
                        || m.contains("403") || m.contains("forbidden")
                        || m.contains("invalid api key") || m.contains("invalid_api_key")
                        || m.contains("authentication") || m.contains("api key not valid")
                        || m.contains("model not found") || m.contains("model_not_found")
                        || m.contains("invalidapikey") || m.contains("invalid_request_error")
                        || m.contains("quota") || m.contains("insufficient_quota")
                        || m.contains("no default model") || m.contains("model configuration")) {
                    return true;
                }
                // 【Review Bug 3】prompt 结构性错误：重试也得同样结果，立即终止
                if (m.contains("context_length_exceeded")
                        || m.contains("context length")
                        || m.contains("maximum context")
                        || m.contains("max_tokens")
                        || m.contains("prompt too long")
                        || m.contains("input is too long")
                        || m.contains("token limit")) {
                    return true;
                }
                // 【Review Bug 2】内容审核过滤：被 safety 挡下的 prompt 重试也是同样结果
                if (m.contains("content_filter")
                        || m.contains("content filter")
                        || m.contains("data_inspection_failed")
                        || (m.contains("safety") && m.contains("block"))) {
                    return true;
                }
                // 基础设施类永久错误：关键字兜底（和类名判断互补，跨语言 SDK 也能抓到）
                if (m.contains("unknown host") || m.contains("no such host")
                        || m.contains("connection refused")
                        || m.contains("pkix path building failed")
                        || m.contains("certificate verify failed")
                        || m.contains("certificate_unknown")
                        || m.contains("ssl handshake")) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 沿 getCause() 遍历到最深，返回根因异常的 "SimpleName: message" 形式。
     * <p>
     * Spring RestClient 会把 HTTP 层异常包装成 ResourceAccessException，外层消息统一是
     * "I/O error on POST request for ...: <rootMsg>"，根因的异常类型（HttpTimeoutException
     * / UnknownHostException / SSLHandshakeException / ConnectException）在最外层是看不到的。
     * UI 截断的时候又会把宝贵的根因关键字（"rootCause=..."）切掉，运维排错几乎盲猜。
     * <p>
     * 拼进最终抛出的 RuntimeException 消息里，UI 就算截断也能在前几十字看见类名。
     */
    private String summarizeRoot(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && cur.getCause() != null && cur.getCause() != cur && depth < 8) {
            cur = cur.getCause();
            depth++;
        }
        String cls = cur != null ? cur.getClass().getSimpleName() : "Unknown";
        String msg = cur != null ? cur.getMessage() : null;
        if (msg == null) return cls;
        // 截短 message 避免把整段 HTML 错误页塞进异常链
        String trimmed = msg.replaceAll("\\s+", " ").trim();
        if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "...";
        return cls + ": " + trimmed;
    }

    /** Transient error marker to route empty responses through the retry path */
    private static class TransientLlmException extends RuntimeException {
        TransientLlmException(String msg) { super(msg); }
    }

    // ==================== RFC-030: Error classification ====================

    /**
     * Classify an exception into an error code aligned with RFC-009 ErrorType.
     *
     * @return error code string: AUTH_ERROR, BILLING, MODEL_NOT_FOUND,
     *         RATE_LIMIT, SERVER_ERROR, TIMEOUT, CONTENT_FILTER, UNKNOWN
     */
    public String classifyErrorCode(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 8) {
            String className = cur.getClass().getSimpleName();
            if ("UnknownHostException".equals(className)
                    || "SSLHandshakeException".equals(className)) {
                return "AUTH_ERROR";
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("401") || m.contains("unauthorized") || m.contains("403")
                        || m.contains("forbidden") || m.contains("invalid api key")
                        || m.contains("invalid_api_key") || m.contains("authentication")) {
                    return "AUTH_ERROR";
                }
                if (m.contains("quota") || m.contains("insufficient_quota") || m.contains("billing")) {
                    return "BILLING";
                }
                if (m.contains("model not found") || m.contains("model_not_found")) {
                    return "MODEL_NOT_FOUND";
                }
                if (m.contains("429") || m.contains("rate_limit") || m.contains("too many requests")) {
                    return "RATE_LIMIT";
                }
                if (m.contains("content_filter") || m.contains("content filter")
                        || m.contains("data_inspection_failed")) {
                    return "CONTENT_FILTER";
                }
                if (m.contains("timeout") || m.contains("timed out")) {
                    return "TIMEOUT";
                }
                if (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504")) {
                    return "SERVER_ERROR";
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return "UNKNOWN";
    }

    // ==================== RFC-031: Methods for template delegation ====================

    /**
     * Repair a single page by regenerating its content.
     * Used by LocalRepairTemplate.
     */
    public void repairSinglePage(Long targetPageId, Long modelId) {
        WikiPageEntity page = pageService.getById(targetPageId);
        if (page == null) {
            log.warn("[Wiki] repairSinglePage: page not found: {}", targetPageId);
            return;
        }

        WikiKnowledgeBaseEntity kb = kbService.getById(page.getKbId());
        if (kb == null) return;

        // Find source raw material
        List<Long> rawIds = parseSourceRawIds(page.getSourceRawIds());
        if (rawIds.isEmpty()) {
            log.warn("[Wiki] repairSinglePage: no source raw IDs for page {}", targetPageId);
            return;
        }

        WikiRawMaterialEntity raw = rawService.getById(rawIds.get(0));
        if (raw == null) return;

        String textContent = rawService.getTextContent(raw);
        if (textContent == null || textContent.isBlank()) return;

        // Use existing two-phase single-page create logic
        String existingPagesIndex = buildExistingPagesIndex(kb.getId());
        String configContent = kb.getConfigContent() != null ? kb.getConfigContent() : "";
        String createSystem = PromptLoader.loadPrompt("wiki/create-page-system");
        String createUserTemplate = PromptLoader.loadPrompt("wiki/create-page-user");
        String createUser = createUserTemplate
                .replace("{config}", configContent)
                .replace("{existing_pages}", existingPagesIndex)
                .replace("{page_slug}", page.getSlug())
                .replace("{page_title}", page.getTitle())
                .replace("{page_summary}", page.getSummary() != null ? page.getSummary() : "")
                .replace("{raw_title}", raw.getTitle())
                .replace("{raw_content}", textContent);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(createSystem),
                new UserMessage(createUser)
        ));
        String response = callLlmWithResilientRetry(prompt, "repair page=" + page.getSlug());
        com.fasterxml.jackson.databind.JsonNode pageJson = parseJsonResponse(response);
        if (pageJson == null) return;

        String content = pageJson.path("content").asText("");
        String summary = pageJson.path("summary").asText("");
        if (!content.isBlank()) {
            pageService.updatePageByAi(kb.getId(), page.getSlug(), content, summary, rawIds.get(0));
            log.info("[Wiki] Repaired page: {} (kbId={})", page.getSlug(), kb.getId());
        }
    }

    private List<Long> parseSourceRawIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) return null;

        String cleaned = response.trim();

        // 1. 剥离 markdown 代码块标记
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // 2. 清洗控制字符（保留 \n \r \t），防止 LLM 输出含不可见字符导致 JSON 解析失败
        cleaned = cleaned.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 3. 第一次尝试直接解析
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            // 4. 如果整体不是 JSON，尝试提取第一个 JSON 对象块（LLM 可能在 JSON 前后加了说明文字）
            int jsonStart = cleaned.indexOf("{");
            int jsonEnd = cleaned.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String extracted = cleaned.substring(jsonStart, jsonEnd + 1);
                try {
                    return objectMapper.readTree(extracted);
                } catch (Exception e2) {
                    log.warn("[Wiki] Failed to parse extracted JSON block: {}", e2.getMessage());
                }
            }
            log.warn("[Wiki] Failed to parse JSON response: {}", e.getMessage());
            return null;
        }
    }
}
