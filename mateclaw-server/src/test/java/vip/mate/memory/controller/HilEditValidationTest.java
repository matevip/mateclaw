package vip.mate.memory.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.memory.model.DreamReportEntity;
import vip.mate.memory.model.MemoryRecallEntity;
import vip.mate.memory.repository.DreamReportMapper;
import vip.mate.memory.repository.MemoryRecallMapper;
import vip.mate.memory.service.MemoryHilService;
import vip.mate.memory.service.MorningCardService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for HiL edit API contract:
 * - Report-scoped edit: key must belong to that report's entry set
 * - Direct edit (reportId=0): key must be an existing MEMORY.md section
 */
@ExtendWith(MockitoExtension.class)
class HilEditValidationTest {

    @Mock private DreamReportMapper dreamReportMapper;
    @Mock private MemoryRecallMapper recallMapper;
    @Mock private MorningCardService morningCardService;
    @Mock private MemoryHilService hilService;
    @Mock private DreamEventBroadcaster eventBroadcaster;

    private DreamController controller;

    @BeforeEach
    void setUp() {
        controller = new DreamController(dreamReportMapper, recallMapper,
                morningCardService, hilService, eventBroadcaster);
    }

    @Test
    @DisplayName("Report-scoped edit: key not in report's candidates → rejected")
    void reportScopedEdit_keyNotInReport_rejected() {
        // Setup: report exists and belongs to agent
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        // No recall entries match the key "unrelated_section"
        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("memory/2026-04-19.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        var result = controller.editEntry(1L, 100L, "unrelated_section",
                Map.of("content", "hacked content"));

        // Should fail — key doesn't belong to this report
        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any());
    }

    @Test
    @DisplayName("Report-scoped edit: key matches report candidate → allowed")
    void reportScopedEdit_keyInReport_allowed() {
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        // Recall entry filename contains the key
        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("MEMORY.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        var result = controller.editEntry(1L, 100L, "deployment_info",
                Map.of("content", "updated content"));

        // Should succeed
        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("deployment_info"), eq("updated content"));
    }

    @Test
    @DisplayName("Report-scoped edit: substring of candidate key → rejected (exact match required)")
    void reportScopedEdit_substringKey_rejected() {
        DreamReportEntity report = new DreamReportEntity();
        report.setId(100L);
        report.setAgentId(1L);
        report.setStartedAt(LocalDateTime.of(2026, 4, 20, 3, 0));
        report.setFinishedAt(LocalDateTime.of(2026, 4, 20, 3, 5));
        report.setDeleted(0);
        lenient().when(dreamReportMapper.selectOne(any())).thenReturn(report);

        MemoryRecallEntity candidate = new MemoryRecallEntity();
        candidate.setFilename("MEMORY.md#deployment_info");
        candidate.setLastRecalledAt(LocalDateTime.of(2026, 4, 20, 3, 2));
        candidate.setDeleted(0);
        lenient().when(recallMapper.selectList(any())).thenReturn(List.of(candidate));

        // "deployment" is a substring of "deployment_info" — must be rejected
        var result = controller.editEntry(1L, 100L, "deployment",
                Map.of("content", "content"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any());
    }

    @Test
    @DisplayName("Direct edit (reportId=0): existing section → allowed")
    void directEdit_existingSection_allowed() {
        when(hilService.sectionExists(1L, "stable_facts")).thenReturn(true);

        var result = controller.editEntry(1L, 0L, "stable_facts",
                Map.of("content", "new content"));

        assertEquals(200, result.getCode());
        verify(hilService).editMemoryEntry(eq(1L), eq("stable_facts"), eq("new content"));
    }

    @Test
    @DisplayName("Direct edit (reportId=0): non-existing section → rejected")
    void directEdit_nonExistingSection_rejected() {
        when(hilService.sectionExists(1L, "ghost_section")).thenReturn(false);

        var result = controller.editEntry(1L, 0L, "ghost_section",
                Map.of("content", "content"));

        assertNotEquals(200, result.getCode());
        verify(hilService, never()).editMemoryEntry(any(), any(), any());
    }
}
