package vip.mate.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.repository.DreamReportMapper;
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
}
