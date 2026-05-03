package vip.mate.skill.runtime;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.lessons.SkillLessonsService;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.skill.mcp.McpSkillBridge;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.runtime.model.ResolvedSkill;
import vip.mate.skill.service.SkillService;

import vip.mate.skill.workspace.SkillWorkspaceEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 技能运行时服务
 * 管理 active skills 运行时视图，提供缓存和刷新机制
 */
@Slf4j
@Service
public class SkillRuntimeService {

    private final SkillService skillService;
    private final SkillPackageResolver packageResolver;
    /**
     * {@code @Lazy} — SkillLessonsService depends on SkillWorkspaceManager,
     * which is constructed early; the lazy proxy avoids a chicken-and-egg
     * cycle when the runtime service initializes alongside the skill
     * service stack. Using setter-style injection through the
     * constructor below.
     */
    private final SkillLessonsService lessonsService;
    /**
     * RFC-090 §3.2 / §10.2 Q2 — MCP-server → virtual-skill bridge.
     * {@code @Lazy} because the bridge depends on McpClientManager
     * which boots later in the lifecycle.
     */
    private final McpSkillBridge mcpSkillBridge;
    /**
     * RFC-090 §3.2 (parallel) — ACP-endpoint → virtual-skill bridge.
     * Same {@code @Lazy} treatment as the MCP bridge.
     */
    private final AcpSkillBridge acpSkillBridge;

    @Autowired
    public SkillRuntimeService(SkillService skillService,
                               SkillPackageResolver packageResolver,
                               @Lazy SkillLessonsService lessonsService,
                               @Lazy McpSkillBridge mcpSkillBridge,
                               @Lazy AcpSkillBridge acpSkillBridge) {
        this.skillService = skillService;
        this.packageResolver = packageResolver;
        this.lessonsService = lessonsService;
        this.mcpSkillBridge = mcpSkillBridge;
        this.acpSkillBridge = acpSkillBridge;
    }

    // 缓存已解析的 active skills（5分钟过期）
    private final Cache<String, List<ResolvedSkill>> activeSkillsCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10)
        .build();

    private static final String CACHE_KEY = "active_skills";

    /**
     * Debounce window for {@link #onWorkspaceEvent}. Startup typically
     * fires one event per bundled skill (30+ in a row), and there's
     * nothing useful to do until the whole batch settles. 500 ms is
     * long enough to swallow the burst without making admin re-syncs
     * feel laggy.
     */
    private static final long REFRESH_DEBOUNCE_MS = 500;

    private final ScheduledExecutorService refreshScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skill-refresh-debouncer");
                t.setDaemon(true);
                return t;
            });

    /** Most recent pending refresh future — atomically swapped on each
     *  event so the previous one can be cancelled. */
    private final AtomicReference<ScheduledFuture<?>> pendingRefresh = new AtomicReference<>();

    @PostConstruct
    public void init() {
        log.info("SkillRuntimeService initialized");
        // 设置反向引用，避免循环依赖
        skillService.setRuntimeService(this);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 延迟到 ApplicationReady 事件触发，确保 SQL 初始化脚本已执行完毕
        refreshActiveSkills();
    }

    @EventListener(SkillWorkspaceEvent.class)
    public void onWorkspaceEvent(SkillWorkspaceEvent event) {
        // Coalesce bursts of workspace events (every bundled-skill sync at
        // startup fires one) into a single refresh. Without debounce, a
        // 30-skill startup triggered 30 sequential refreshActiveSkills()
        // calls — each running the whole resolve+scan loop. With 500 ms
        // debounce it collapses to one.
        log.debug("Workspace event: {} {} (refresh scheduled in {}ms)",
                event.type(), event.skillName(), REFRESH_DEBOUNCE_MS);
        ScheduledFuture<?> previous = pendingRefresh.get();
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
        ScheduledFuture<?> task = refreshScheduler.schedule(() -> {
            try {
                refreshActiveSkills();
            } catch (Exception e) {
                log.warn("Debounced refresh failed: {}", e.getMessage());
            }
        }, REFRESH_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingRefresh.set(task);
    }

    @PreDestroy
    public void shutdown() {
        // Drain any pending refresh so JVM shutdown doesn't hang on the
        // daemon thread, even though it's marked daemon and would die anyway.
        ScheduledFuture<?> pending = pendingRefresh.getAndSet(null);
        if (pending != null) pending.cancel(true);
        refreshScheduler.shutdown();
        try {
            if (!refreshScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                refreshScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            refreshScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * RFC-090 §14.1 — single source of truth for "is this resolved
     * skill currently exposable to an agent". Both the global
     * {@link #refreshActiveSkills()} cache and the per-agent
     * {@link #buildSkillPromptEnhancement(Set)} branch route through
     * here so the two views can never disagree.
     *
     * <p>Rules:
     * <ol>
     *   <li>row enabled, runtime resolved, not security-blocked — required</li>
     *   <li>manifest present → at least one feature READY ({@code hasAnyActiveFeature})</li>
     *   <li>manifest absent (legacy SKILL.md) → fall back to
     *       {@code dependencyReady} so old skills behave unchanged</li>
     * </ol>
     */
    public static boolean passesActiveGate(ResolvedSkill s) {
        if (s == null) return false;
        if (!s.isEnabled() || !s.isRuntimeAvailable() || s.isSecurityBlocked()) return false;
        if (s.getManifest() == null) return s.isDependencyReady();
        return s.hasAnyActiveFeature();
    }

    /**
     * 获取当前启用的技能列表（运行时视图）
     */
    public List<ResolvedSkill> getActiveSkills() {
        List<ResolvedSkill> cached = activeSkillsCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            return cached;
        }
        return refreshActiveSkills();
    }

    /**
     * 刷新 active skills 缓存
     * 进入 active set 的 skill 必须同时满足：
     * 1. enabled == true
     * 2. runtimeAvailable == true
     * 3. securityBlocked == false
     * 4. dependencyReady == true
     */
    public List<ResolvedSkill> refreshActiveSkills() {
        List<SkillEntity> enabledSkills = skillService.listEnabledSkills();

        List<ResolvedSkill> resolved = enabledSkills.stream()
            .map(packageResolver::resolve)
            .filter(SkillRuntimeService::passesActiveGate)
            .collect(Collectors.toList());
        // Track real skill names so a same-named bridged virtual skill is
        // suppressed (real wins). Without this, a real SKILL.md packaged
        // alongside a same-name MCP/ACP server produces two cards on the
        // Skills page.
        Set<String> realNames = resolved.stream()
            .map(ResolvedSkill::getName)
            .collect(Collectors.toSet());
        try {
            // MCP-derived virtual skills go through the same active gate
            // so a disconnected MCP server doesn't pollute the prompt
            // enhancement. Same-name virtuals are suppressed by the real
            // skill above.
            for (ResolvedSkill virt : mcpSkillBridge.listMcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                if (passesActiveGate(virt)) resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("MCP skill bridge active merge failed: {}", e.getMessage());
        }
        try {
            // ACP-derived virtual skills. Same dedup as MCP.
            for (ResolvedSkill virt : acpSkillBridge.listAcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                if (passesActiveGate(virt)) resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("ACP skill bridge active merge failed: {}", e.getMessage());
        }

        activeSkillsCache.put(CACHE_KEY, resolved);
        log.info("Refreshed active skills: {} enabled", resolved.size());

        return resolved;
    }

    /**
     * 解析所有技能的运行时状态（管理页面使用，包含 disabled 和 error 信息）
     *
     * <p>RFC-090 §3.2 — appends virtual MCP-derived skills so the Skills
     * page can render MCP servers as first-class skill cards. Real
     * skills resolve through the full pipeline; virtual ones come
     * pre-built from {@link McpSkillBridge}.
     */
    public List<ResolvedSkill> resolveAllSkillsStatus() {
        List<SkillEntity> allSkills = skillService.listSkills();
        List<ResolvedSkill> resolved = allSkills.stream()
            .map(packageResolver::resolve)
            .collect(Collectors.toList());
        // Same dedup-by-name as refreshActiveSkills(): a real skill with
        // the same name as a bridged virtual one suppresses the virtual,
        // so the Skills admin page never shows two cards for the same name.
        Set<String> realNames = resolved.stream()
            .map(ResolvedSkill::getName)
            .collect(Collectors.toSet());
        try {
            for (ResolvedSkill virt : mcpSkillBridge.listMcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("MCP skill bridge merge failed: {}", e.getMessage());
        }
        try {
            for (ResolvedSkill virt : acpSkillBridge.listAcpDerivedResolvedSkills()) {
                if (realNames.contains(virt.getName())) continue;
                resolved.add(virt);
            }
        } catch (Exception e) {
            log.warn("ACP skill bridge merge failed: {}", e.getMessage());
        }
        return resolved;
    }

    /**
     * Rescan one skill on demand (RFC-042 §2.3.4) — runs the full resolver
     * pipeline (content + security + dependency), which writes the updated
     * scan result to DB as a side-effect, and then invalidates the active
     * skills cache so subsequent reads reflect the new status.
     */
    public ResolvedSkill rescanSingle(SkillEntity skill) {
        ResolvedSkill resolved = packageResolver.resolve(skill);
        activeSkillsCache.invalidateAll();
        log.info("Rescanned skill '{}' (id={}): status={}, blocked={}",
                skill.getName(), skill.getId(),
                skill.getSecurityScanStatus(), resolved.isSecurityBlocked());
        return resolved;
    }

    /**
     * RFC-090 review #3 — explicit lifecycle hook so SkillService
     * can deregister wrapper tools without poking at the resolver
     * directly. Safe to call for skill ids that never had wrappers.
     */
    public void deregisterSkillWrappers(Long skillId) {
        try {
            packageResolver.deregisterSkillWrappers(skillId);
        } catch (Exception e) {
            log.warn("Failed to deregister wrappers for skill {}: {}", skillId, e.getMessage());
        }
    }

    /**
     * 根据名称查找 active skill
     */
    public ResolvedSkill findActiveSkill(String name) {
        return getActiveSkills().stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 构建技能 prompt 增强片段（全局，向后兼容）
     */
    public String buildSkillPromptEnhancement() {
        return buildSkillPromptEnhancement(null);
    }

    /**
     * 构建技能 prompt 增强片段（支持 per-agent 过滤）
     *
     * @param boundSkillIds Agent 绑定的 skill ID 集合。null 表示使用全局默认（无绑定）。
     *                      非 null 时仅包含指定 ID 的 skill。
     */
    public String buildSkillPromptEnhancement(Set<Long> boundSkillIds) {
        List<ResolvedSkill> activeSkills;
        if (boundSkillIds != null) {
            // Per-agent 过滤：从全局 enabled skills 中按 ID 过滤。RFC-090
            // §14.1 — must use the same features-aware gate as
            // refreshActiveSkills() so legacy dependencyReady drift
            // doesn't silently let setup-needed manifest skills through
            // (or hide partially-ready features that should be visible).
            List<SkillEntity> enabledSkills = skillService.listEnabledSkills();
            activeSkills = enabledSkills.stream()
                    .filter(s -> boundSkillIds.contains(s.getId()))
                    .map(packageResolver::resolve)
                    .filter(SkillRuntimeService::passesActiveGate)
                    .collect(java.util.stream.Collectors.toList());
        } else {
            activeSkills = getActiveSkills();
        }
        // Platform filter — drop skills whose `platforms:` frontmatter
        // names a different OS than the runtime host. apple-notes /
        // findmy etc. are macOS-only; surfacing them on Linux just
        // burns prompt tokens for skills the user can never run.
        // Empty / missing `platforms:` means "all platforms" (the default).
        String currentOs = currentOsCanonical();
        activeSkills = activeSkills.stream()
                .filter(s -> matchesCurrentPlatform(s, currentOs))
                .collect(java.util.stream.Collectors.toList());
        if (activeSkills.isEmpty()) {
            return "";
        }

        // Compact preamble — was ~1 KB of warnings before. The two failure
        // modes that drove the older wording (#46: LLM treats skill name
        // as a tool; #49: LLM invokes runSkillScript on a docs-only skill)
        // are now addressed in two cheaper spots:
        //   - readSkillFile/runSkillScript tools have explicit "Tool not
        //     found" errors that nudge the model to retry the right way.
        //   - SKILL.md itself, once loaded via readSkillFile, tells the
        //     model whether to invoke a script or just follow the prose.
        // 49 skills × ~20 chars/row saved by dropping the Shape column +
        // ~600 chars saved by trimming the preamble = ~1.5 KB / ~400
        // tokens lighter on every chat request.
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Skills\n");
        sb.append("Before answering, scan the skills below. If a skill matches your task, ");
        sb.append("load it via `readSkillFile(skillName=<name>, filePath=\"SKILL.md\")` and follow its instructions. ");
        sb.append("Skills are documentation packages — calling a skill name as a tool will fail. ");
        sb.append("Skills with a `scripts/` directory expose `runSkillScript`; SKILL.md will name the script when needed.\n\n");
        sb.append("| Skill | Description |\n");
        sb.append("|-------|-------------|\n");
        for (ResolvedSkill skill : activeSkills) {
            sb.append("| `").append(skill.getName()).append("`");
            if (skill.getIcon() != null && !skill.getIcon().isBlank()) {
                sb.append(" ").append(skill.getIcon());
            }
            sb.append(" | ");
            if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
                String desc = skill.getDescription();
                if (desc.length() > 200) {
                    desc = desc.substring(0, 200) + "...";
                }
                // Escape pipe and newline so a multi-line description doesn't
                // break the table layout.
                sb.append(desc.replace("|", "\\|").replace("\n", " "));
            }
            sb.append(" |\n");
        }

        // RFC-090 §11.4.3 + §10.2 Q6 — append per-skill LESSONS.md after
        // the catalog so the LLM sees "Available Skills" first, then any
        // accumulated lessons attached to each skill that has opted in.
        appendLessonsBlock(sb, activeSkills);

        return sb.toString();
    }

    /**
     * Append a "## Lessons learned" block to the prompt enhancement
     * with one subsection per active skill that has lessons recorded.
     *
     * <p>Skills opt in via {@code self-evolution.lessons_enabled} (default
     * true). Skills with no LESSONS.md content contribute nothing — we
     * never emit an empty subsection.
     */
    private void appendLessonsBlock(StringBuilder sb, List<ResolvedSkill> activeSkills) {
        if (lessonsService == null || activeSkills == null || activeSkills.isEmpty()) return;
        StringBuilder lessons = new StringBuilder();
        for (ResolvedSkill skill : activeSkills) {
            SkillManifest manifest = skill.getManifest();
            boolean enabled = manifest == null
                    || manifest.getSelfEvolution() == null
                    || manifest.getSelfEvolution().isLessonsEnabled();
            if (!enabled) continue;
            String body = lessonsService.readLessonsBody(skill);
            if (body == null || body.isBlank()) continue;
            lessons.append("\n### ").append(skill.getName()).append("\n");
            lessons.append(body).append("\n");
        }
        if (lessons.length() > 0) {
            sb.append("\n\n## Lessons learned\n");
            sb.append("Past observations the agent recorded for these skills. ");
            sb.append("Treat them as advisory hints — the canonical SKILL.md still wins on conflict.\n");
            sb.append(lessons);
        }
    }

    /**
     * Map {@code System.getProperty("os.name")} to one of the canonical
     * tokens used in SKILL.md {@code platforms:} ({@code macos / linux /
     * windows}). Anything unrecognised → {@code "other"} which never
     * matches a declared platform list, so the skill stays visible only
     * if its platforms list is empty (the "all platforms" default).
     */
    static String currentOsCanonical() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix")) return "linux";
        if (os.contains("win")) return "windows";
        return "other";
    }

    /**
     * True when the skill is compatible with {@code currentOs}. A skill
     * with empty / null {@code platforms:} matches every OS (legacy
     * default). Otherwise the canonical OS token must appear in the list.
     */
    static boolean matchesCurrentPlatform(ResolvedSkill skill, String currentOs) {
        SkillManifest manifest = skill.getManifest();
        if (manifest == null) return true;
        List<String> platforms = manifest.getPlatforms();
        if (platforms == null || platforms.isEmpty()) return true;
        for (String p : platforms) {
            if (p == null) continue;
            if (currentOs.equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }
}
