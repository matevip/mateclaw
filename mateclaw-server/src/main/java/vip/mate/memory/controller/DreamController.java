package vip.mate.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.service.MorningCardService;
import vip.mate.memory.service.MemoryHilService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dream report API — provides paginated access to dream history for the Memory Timeline UI.
 *
 * @author MateClaw Team
 */
@Tag(name = "Dream Reports")
@RestController
@RequestMapping("/api/v1/memory/{agentId}/dream")
@RequiredArgsConstructor
public class DreamController {

    private final DreamReportMapper dreamReportMapper;
    private final MorningCardService morningCardService;
    private final MemoryHilService hilService;

    @Operation(summary = "List dream reports (paginated, newest first)")
    @GetMapping("/reports")
    @RequireWorkspaceRole("viewer")
    public R<Map<String, Object>> listReports(
            @PathVariable Long agentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DreamReportEntity> pageParam = new Page<>(page, size);
        Page<DreamReportEntity> result = dreamReportMapper.selectPage(pageParam,
                new LambdaQueryWrapper<DreamReportEntity>()
                        .eq(DreamReportEntity::getAgentId, agentId)
                        .eq(DreamReportEntity::getDeleted, 0)
                        .orderByDesc(DreamReportEntity::getStartedAt));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        return R.ok(data);
    }

    @Operation(summary = "Get a single dream report by ID")
    @GetMapping("/reports/{reportId}")
    @RequireWorkspaceRole("viewer")
    public R<DreamReportEntity> getReport(
            @PathVariable Long agentId,
            @PathVariable Long reportId) {
        DreamReportEntity entity = dreamReportMapper.selectOne(
                new LambdaQueryWrapper<DreamReportEntity>()
                        .eq(DreamReportEntity::getId, reportId)
                        .eq(DreamReportEntity::getAgentId, agentId)
                        .eq(DreamReportEntity::getDeleted, 0));
        if (entity == null) {
            return R.fail("Report not found");
        }
        return R.ok(entity);
    }

    // ==================== Morning Card ====================

    @Operation(summary = "Get morning card for current user + agent")
    @GetMapping("/morning-card")
    @RequireWorkspaceRole("viewer")
    public R<Map<String, Object>> getMorningCard(@PathVariable Long agentId, Authentication auth) {
        Long userId = resolveUserId(auth);
        if (userId == null) return R.fail("Not authenticated");
        Map<String, Object> card = morningCardService.getCardFor(userId, agentId);
        return R.ok(card); // null = no card to show
    }

    @Operation(summary = "Mark morning card as seen")
    @PostMapping("/morning-card/seen")
    @RequireWorkspaceRole("viewer")
    public R<Void> markMorningCardSeen(@PathVariable Long agentId,
                                        @RequestBody Map<String, Object> body,
                                        Authentication auth) {
        Long userId = resolveUserId(auth);
        if (userId == null) return R.fail("Not authenticated");
        Long reportId = body.get("reportId") != null
                ? Long.valueOf(body.get("reportId").toString()) : null;
        morningCardService.markSeen(userId, agentId, reportId);
        return R.ok(null);
    }

    // ==================== HiL (Human-in-the-Loop) ====================

    @Operation(summary = "Confirm a memory entry (no-op acknowledgment)")
    @PostMapping("/reports/{reportId}/entries/{key}/confirm")
    @RequireWorkspaceRole("member")
    public R<Void> confirmEntry(@PathVariable Long agentId,
                                 @PathVariable Long reportId,
                                 @PathVariable String key) {
        // Confirm is a no-op in Phase 2 — just logs the action
        return R.ok(null);
    }

    @Operation(summary = "Edit a memory entry — writes back to MEMORY.md with user-edited metadata")
    @PostMapping("/reports/{reportId}/entries/{key}/edit")
    @RequireWorkspaceRole("member")
    public R<Void> editEntry(@PathVariable Long agentId,
                              @PathVariable Long reportId,
                              @PathVariable String key,
                              @RequestBody Map<String, String> body) {
        String newContent = body.get("content");
        if (newContent == null || newContent.isBlank()) {
            return R.fail("content is required");
        }
        hilService.editMemoryEntry(agentId, key, newContent);
        return R.ok(null);
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null) return null;
        // Resolve from auth principal — assumes user ID is accessible
        try {
            Object principal = auth.getPrincipal();
            if (principal instanceof vip.mate.auth.model.UserEntity user) {
                return user.getId();
            }
            // Fallback: use username hash as stable ID
            return (long) auth.getName().hashCode();
        } catch (Exception e) {
            return null;
        }
    }
}
