package vip.mate.activity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.audit.model.AuditEventEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC-090 §4.5 / §7 — unified Activity feed.
 *
 * <p>Merges three sources into one chronologically-ordered stream:
 * <ul>
 *   <li>{@code audit_event} — CRUD-style events on agents / channels /
 *       skills / wiki / workspace (the existing audit log)</li>
 *   <li>{@code tool_approval} — approval requests + their resolution
 *       (granted / denied / expired). Ties tool gating decisions
 *       directly to the audit timeline.</li>
 *   <li>Successful tool calls — RFC §4.5 mentions these, but the
 *       runtime doesn't yet persist a row per successful call.
 *       Returning an empty bucket keeps the API contract stable so
 *       the UI can light up automatically once a future commit adds
 *       persistence.</li>
 * </ul>
 *
 * <p>Pagination is best-effort: each source is paged from index 0
 * up to {@code size * 2}, then the merged list is trimmed and offset
 * in-memory. For workspaces with >>1k events / day a follow-up should
 * push merging into SQL; this is good enough for v1.
 */
@Tag(name = "Activity Feed (RFC-090)")
@RestController
@RequestMapping("/api/v1/activity")
@RequiredArgsConstructor
public class ActivityFeedController {

    private final AuditEventService auditEventService;
    private final ToolApprovalMapper toolApprovalMapper;

    @Operation(summary = "Unified activity feed (audit + approval + tool calls)")
    @GetMapping("/feed")
    public R<Map<String, Object>> feed(
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size <= 0 || size > 200) size = 20;
        if (page <= 0) page = 1;

        // Pull a chunk from each source large enough to cover the
        // requested page after merge. We read at most page*size rows
        // from each side; for stable cursors, future iterations should
        // push the merge into SQL with a UNION ALL view.
        int chunk = Math.max(size * page, 50);

        List<ActivityRow> rows = new ArrayList<>();

        boolean wantAudit = source == null || source.isBlank() || "audit".equalsIgnoreCase(source);
        boolean wantApproval = source == null || source.isBlank() || "approval".equalsIgnoreCase(source);

        if (wantAudit) {
            try {
                IPage<AuditEventEntity> audit = auditEventService.listEvents(
                        workspaceId, null, null, null, null, 1, chunk);
                for (AuditEventEntity ev : audit.getRecords()) {
                    rows.add(fromAuditEvent(ev));
                }
            } catch (Exception ignored) { /* surface silence > one-source crash */ }
        }
        if (wantApproval) {
            try {
                Page<ToolApprovalEntity> p = new Page<>(1, chunk);
                LambdaQueryWrapper<ToolApprovalEntity> qw = new LambdaQueryWrapper<ToolApprovalEntity>()
                        .orderByDesc(ToolApprovalEntity::getCreatedAt);
                IPage<ToolApprovalEntity> approvals = toolApprovalMapper.selectPage(p, qw);
                for (ToolApprovalEntity ap : approvals.getRecords()) {
                    rows.add(fromApproval(ap));
                }
            } catch (Exception ignored) { /* see above */ }
        }

        rows.sort(Comparator.comparing(ActivityRow::time, Comparator.nullsLast(Comparator.reverseOrder())));

        long total = rows.size();
        int from = Math.min((page - 1) * size, rows.size());
        int to = Math.min(from + size, rows.size());
        List<ActivityRow> sliced = rows.subList(from, to);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("page", page);
        resp.put("size", size);
        resp.put("total", total);
        resp.put("records", sliced);
        return R.ok(resp);
    }

    private ActivityRow fromAuditEvent(AuditEventEntity ev) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("detailJson", ev.getDetailJson());
        detail.put("userAgent", ev.getUserAgent());
        detail.put("workspaceId", ev.getWorkspaceId());
        return new ActivityRow(
                "audit-" + ev.getId(),
                "audit",
                ev.getCreateTime(),
                ev.getUsername(),
                ev.getAction(),
                ev.getResourceType(),
                ev.getResourceName() != null ? ev.getResourceName() : ev.getResourceId(),
                ev.getIpAddress(),
                detail);
    }

    private ActivityRow fromApproval(ToolApprovalEntity ap) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("toolArguments", ap.getToolArguments());
        detail.put("summary", ap.getSummary());
        detail.put("maxSeverity", ap.getMaxSeverity());
        detail.put("status", ap.getStatus());
        detail.put("resolvedAt", ap.getResolvedAt());
        // Map approval status onto an audit-style action so the UI's
        // existing action coloring (CREATE / DELETE / etc.) keeps
        // working without a special case.
        String action = "APPROVAL_" + (ap.getStatus() == null ? "PENDING" : ap.getStatus().toUpperCase());
        return new ActivityRow(
                "approval-" + ap.getId(),
                "approval",
                ap.getCreatedAt(),
                ap.getResolvedBy() != null ? ap.getResolvedBy() : ap.getRequesterName(),
                action,
                "TOOL_APPROVAL",
                ap.getToolName(),
                null,
                detail);
    }

    /**
     * Wire-format row. Public record so Jackson serializes it directly
     * without needing a separate DTO.
     */
    public record ActivityRow(
            String id,
            String source,
            LocalDateTime time,
            String username,
            String action,
            String resourceType,
            String resourceName,
            String ipAddress,
            Map<String, Object> detail
    ) {}
}
