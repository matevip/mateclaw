package vip.mate.wiki;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Wiki 知识库配置
 *
 * @author MateClaw Team
 */
@Data
@ConfigurationProperties(prefix = "mate.wiki")
public class WikiProperties {

    /** 是否启用 Wiki 知识库功能 */
    private boolean enabled = true;

    /**
     * LLM 单次处理最大字符数（超过则分块）。
     * <p>
     * RFC-012：默认从 30000 下调到 15000 —— 单 chunk 输出 tokens 砍半、并行饱和度更高、
     * 质量也更稳定。中端模型（qwen-plus/claude-sonnet）建议 12000-20000，
     * 旗舰模型（qwen-max/claude-opus）可调大到 20000-30000。
     */
    private int maxChunkSize = 15000;

    /**
     * 同一次 processAllPending 下同时处理的原始材料数上限。
     * <p>
     * RFC-012 Change 1：保护共享的 @Async 线程池（max=16）不被 wiki 长时间占满，
     * 同时给材料级并发定一个可控的上限，避免 LLM 提供方触发限流。
     */
    private int maxParallelRawMaterials = 3;

    /**
     * 单个材料内 chunk 的并行处理数上限。
     * <p>
     * RFC-012 Change 1：从硬编码 3 提到 5，并暴露为配置项。默认总并发为
     * maxParallelRawMaterials × maxParallelChunks = 15，仍在常见 60 RPM 限额下。
     */
    private int maxParallelChunks = 5;

    /** 注入 agent prompt 的最大字符数 */
    private int maxContextChars = 10000;

    /** 单个原始材料最多生成的 Wiki 页面数 */
    private int maxPagesPerRaw = 15;

    /** 上传后是否自动触发处理 */
    private boolean autoProcessOnUpload = true;

    /** 上传文件存储目录 */
    private String uploadDir = "./data/wiki-uploads";

    /** 目录扫描最大文件数 */
    private int maxScanFiles = 500;

    /** 扫描时跳过大于此大小的文件（字节），默认 50MB */
    private long maxScanFileSize = 50 * 1024 * 1024;

    /**
     * Wiki LLM 重试最大尝试次数（含首次）。
     * <p>
     * RFC-012 M1：旧实现无最大次数，遇到 nginx 504 这种"反复瞬时"错误会永远重试。
     * 设为 5 后单 chunk 最多走 5 轮，配合 llmMaxTotalDurationMs 共同保证有界停止。
     */
    private int llmMaxAttempts = 5;

    /**
     * Wiki LLM 重试总耗时上限（毫秒），从首次调用开始计时。
     * <p>
     * RFC-012 M1：单 chunk LLM 调用 + 重试的硬封顶，超过即放弃，让该 chunk 进入 failed 计数。
     * 默认 4 分钟。
     */
    private long llmMaxTotalDurationMs = 240_000;

    /**
     * 是否启用两阶段消化（路由 → 逐页 merge）。
     * <p>
     * RFC-012 M2：true 时单 chunk 的 LLM 输出量大幅缩减，避免 nginx 60s 网关超时。
     * 默认 true（M2 上线）；遇问题可在 application.yml 配 mate.wiki.use-two-phase-digest=false 回退到旧行为。
     */
    private boolean useTwoPhaseDigest = true;
}
