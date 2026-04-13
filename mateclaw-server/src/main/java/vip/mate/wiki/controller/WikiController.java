package vip.mate.wiki.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.event.WikiProcessingEvent;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.model.WikiRawMaterialEntity;
import vip.mate.wiki.service.WikiDirectoryScanService;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiProcessingService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wiki 知识库接口
 *
 * @author MateClaw Team
 */
@Tag(name = "Wiki 知识库")
@RestController
@RequestMapping("/api/v1/wiki")
@RequiredArgsConstructor
public class WikiController {

    private final WikiKnowledgeBaseService kbService;
    private final WikiRawMaterialService rawService;
    private final WikiPageService pageService;
    private final WikiProcessingService processingService;
    private final WikiDirectoryScanService scanService;
    private final WikiProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    // ==================== Knowledge Base ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取所有知识库")
    @GetMapping("/knowledge-bases")
    public R<List<WikiKnowledgeBaseEntity>> listKBs(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(kbService.listByWorkspace(wsId));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取知识库详情")
    @GetMapping("/knowledge-bases/{id}")
    public R<WikiKnowledgeBaseEntity> getKB(@PathVariable Long id,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return R.fail("Knowledge base not found");
        return R.ok(kb);
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "按 Agent 获取知识库")
    @GetMapping("/knowledge-bases/agent/{agentId}")
    public R<List<WikiKnowledgeBaseEntity>> listKBsByAgent(@PathVariable Long agentId,
                                                            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        // 按 agent 查询后，过滤出属于当前 workspace 的知识库
        List<WikiKnowledgeBaseEntity> kbs = kbService.listByAgentId(agentId);
        return R.ok(kbs.stream()
                .filter(kb -> kb.getWorkspaceId() == null || kb.getWorkspaceId().equals(wsId))
                .toList());
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "创建知识库")
    @PostMapping("/knowledge-bases")
    public R<WikiKnowledgeBaseEntity> createKB(@RequestBody Map<String, Object> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        long wsId = workspaceId != null ? workspaceId : 1L;
        WikiKnowledgeBaseEntity kb = kbService.create(name, description, agentId, wsId);
        return R.ok(kb);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "更新知识库")
    @PutMapping("/knowledge-bases/{id}")
    public R<WikiKnowledgeBaseEntity> updateKB(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        Long agentId = body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null;
        return R.ok(kbService.update(id, name, description, agentId));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除知识库")
    @DeleteMapping("/knowledge-bases/{id}")
    public R<Void> deleteKB(@PathVariable Long id,
                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        kbService.delete(id);
        return R.ok();
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取知识库配置")
    @GetMapping("/knowledge-bases/{id}/config")
    public R<Map<String, String>> getConfig(@PathVariable Long id,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(id);
        if (kb == null) return R.fail("Knowledge base not found");
        return R.ok(Map.of("content", kb.getConfigContent() != null ? kb.getConfigContent() : ""));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "更新知识库配置")
    @PutMapping("/knowledge-bases/{id}/config")
    public R<Void> updateConfig(@PathVariable Long id, @RequestBody Map<String, String> body,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        kbService.updateConfig(id, body.get("content"));
        return R.ok();
    }

    // ==================== Directory Scan ====================

    @RequireWorkspaceRole("member")
    @Operation(summary = "设置知识库关联目录")
    @PutMapping("/knowledge-bases/{id}/source-directory")
    public R<Void> setSourceDirectory(@PathVariable Long id, @RequestBody Map<String, String> body,
                                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        String path = body.get("path");
        kbService.updateSourceDirectory(id, path);
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "扫描关联目录导入文件")
    @PostMapping("/knowledge-bases/{id}/scan")
    public R<Map<String, Object>> scanDirectory(@PathVariable Long id,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(id, workspaceId);
        WikiDirectoryScanService.ScanResult result = scanService.scan(id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanned", result.scanned());
        response.put("added", result.added());
        response.put("skipped", result.skipped());
        response.put("errors", result.errors());
        return R.ok(response);
    }

    // ==================== Raw Materials ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取原始材料列表")
    @GetMapping("/knowledge-bases/{kbId}/raw")
    public R<List<WikiRawMaterialEntity>> listRaw(@PathVariable Long kbId,
                                                    @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(rawService.listByKbId(kbId));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "添加文本材料")
    @PostMapping("/knowledge-bases/{kbId}/raw/text")
    public R<WikiRawMaterialEntity> addRawText(@PathVariable Long kbId, @RequestBody Map<String, String> body,
                                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        String title = body.get("title");
        String content = body.get("content");
        return R.ok(rawService.addText(kbId, title, content));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "上传文件材料")
    @PostMapping("/knowledge-bases/{kbId}/raw/upload")
    public R<WikiRawMaterialEntity> uploadRaw(@PathVariable Long kbId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) throws IOException {
        verifyKBWorkspace(kbId, workspaceId);
        String originalName = file.getOriginalFilename();
        String extension = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase()
                : "txt";

        // 确定 sourceType
        String sourceType = switch (extension) {
            case "pdf" -> "pdf";
            case "docx", "doc" -> "docx";
            case "txt", "md" -> "text";
            default -> "text";
        };

        if ("text".equals(sourceType)) {
            // 文本文件直接读取内容
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return R.ok(rawService.addText(kbId, originalName, content));
        } else {
            // 二进制文件保存到磁盘（转绝对路径，避免 Tomcat 临时目录解析问题）
            Path uploadDir = Paths.get(properties.getUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(uploadDir);
            Path targetPath = uploadDir.resolve(System.currentTimeMillis() + "_" + originalName);
            file.transferTo(targetPath);
            return R.ok(rawService.addFile(kbId, originalName, sourceType,
                    targetPath.toString(), file.getSize()));
        }
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除原始材料")
    @DeleteMapping("/knowledge-bases/{kbId}/raw/{rawId}")
    public R<Void> deleteRaw(@PathVariable Long kbId, @PathVariable Long rawId,
                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return R.fail("Raw material not found in this knowledge base");
        }
        rawService.delete(rawId);
        kbService.decrementRawCount(kbId);
        return R.ok();
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "重新处理原始材料")
    @PostMapping("/knowledge-bases/{kbId}/raw/{rawId}/reprocess")
    public R<Void> reprocessRaw(@PathVariable Long kbId, @PathVariable Long rawId,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiRawMaterialEntity raw = rawService.getById(rawId);
        if (raw == null || !kbId.equals(raw.getKbId())) {
            return R.fail("Raw material not found in this knowledge base");
        }
        rawService.reprocess(rawId);
        return R.ok();
    }

    // ==================== Wiki Pages ====================

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取 Wiki 页面列表")
    @GetMapping("/knowledge-bases/{kbId}/pages")
    public R<List<WikiPageEntity>> listPages(@PathVariable Long kbId,
                                              @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.listByKbId(kbId));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取 Wiki 页面内容")
    @GetMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<WikiPageEntity> getPage(@PathVariable Long kbId, @PathVariable String slug,
                                      @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiPageEntity page = pageService.getBySlug(kbId, slug);
        if (page == null) return R.fail("Page not found");
        return R.ok(page);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "手动编辑 Wiki 页面")
    @PutMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<WikiPageEntity> updatePage(@PathVariable Long kbId, @PathVariable String slug,
                                         @RequestBody Map<String, String> body,
                                         @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.updatePageManually(kbId, slug, body.get("content"), body.get("summary")));
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除 Wiki 页面")
    @DeleteMapping("/knowledge-bases/{kbId}/pages/{slug}")
    public R<Void> deletePage(@PathVariable Long kbId, @PathVariable String slug,
                               @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        pageService.delete(kbId, slug);
        kbService.setPageCount(kbId, pageService.countByKbId(kbId));
        return R.ok();
    }

    @Operation(summary = "批量删除 Wiki 页面")
    @DeleteMapping("/knowledge-bases/{kbId}/pages/batch")
    public R<Integer> batchDeletePages(@PathVariable Long kbId,
                                        @RequestBody List<String> slugs,
                                        @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        int deleted = pageService.batchDelete(kbId, slugs);
        kbService.setPageCount(kbId, pageService.countByKbId(kbId));
        return R.ok(deleted);
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取反向链接")
    @GetMapping("/knowledge-bases/{kbId}/pages/{slug}/backlinks")
    public R<List<WikiPageEntity>> getBacklinks(@PathVariable Long kbId, @PathVariable String slug,
                                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        return R.ok(pageService.getBacklinks(kbId, slug));
    }

    // ==================== Processing ====================

    @RequireWorkspaceRole("member")
    @Operation(summary = "触发知识库处理（异步）")
    @PostMapping("/knowledge-bases/{kbId}/process")
    public R<Map<String, Object>> processKB(@PathVariable Long kbId,
                                             @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        List<WikiRawMaterialEntity> pending = rawService.listPending(kbId);
        for (WikiRawMaterialEntity raw : pending) {
            eventPublisher.publishEvent(new WikiProcessingEvent(this, raw.getId(), kbId));
        }
        return R.ok(Map.of("queued", pending.size()));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取处理状态")
    @GetMapping("/knowledge-bases/{kbId}/processing-status")
    public R<Map<String, Object>> getProcessingStatus(@PathVariable Long kbId,
                                                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        verifyKBWorkspace(kbId, workspaceId);
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) return R.fail("Knowledge base not found");

        List<WikiRawMaterialEntity> rawList = rawService.listByKbId(kbId);
        long pending = rawList.stream().filter(r -> "pending".equals(r.getProcessingStatus())).count();
        long processing = rawList.stream().filter(r -> "processing".equals(r.getProcessingStatus())).count();
        long completed = rawList.stream().filter(r -> "completed".equals(r.getProcessingStatus())).count();
        long failed = rawList.stream().filter(r -> "failed".equals(r.getProcessingStatus())).count();

        return R.ok(Map.of(
                "status", kb.getStatus(),
                "pending", pending,
                "processing", processing,
                "completed", completed,
                "failed", failed,
                "totalRaw", rawList.size(),
                "totalPages", kb.getPageCount()
        ));
    }

    // ==================== Workspace Verification ====================

    private void verifyKBWorkspace(Long kbId, Long headerWorkspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new MateClawException("Knowledge base not found");
        }
        long wsId = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (kb.getWorkspaceId() != null && !kb.getWorkspaceId().equals(wsId)) {
            throw new MateClawException("err.common.wrong_workspace", "资源不属于当前工作区");
        }
    }
}
