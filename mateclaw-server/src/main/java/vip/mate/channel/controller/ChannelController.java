package vip.mate.channel.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * 渠道管理接口
 * <p>
 * 提供渠道的 CRUD、启用/禁用（联动 ChannelManager 生命周期）、状态查询等能力。
 * 对应前端 Channel 管理页面。
 *
 * @author MateClaw Team
 */
@Tag(name = "渠道管理")
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelManager channelManager;
    private final AuditEventService auditEventService;

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取渠道列表")
    @GetMapping
    public R<List<ChannelEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        return R.ok(channelService.listChannelsByWorkspace(wsId));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "按类型获取渠道列表")
    @GetMapping("/type/{channelType}")
    public R<List<ChannelEntity>> listByType(@PathVariable String channelType) {
        return R.ok(channelService.listChannelsByType(channelType));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取渠道详情")
    @GetMapping("/{id}")
    public R<ChannelEntity> get(@PathVariable Long id,
                                @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        return R.ok(channel);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "创建渠道")
    @PostMapping
    public R<ChannelEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody ChannelEntity channel) {
        channel.setWorkspaceId(workspaceId != null ? workspaceId : 1L);
        ChannelEntity created = channelService.createChannel(channel);
        auditEventService.record("CREATE", "CHANNEL", String.valueOf(created.getId()), created.getName(), null);
        return R.ok(created);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "更新渠道")
    @PutMapping("/{id}")
    public R<ChannelEntity> update(@PathVariable Long id, @RequestBody ChannelEntity channel,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        channel.setId(id);
        channel.setWorkspaceId(existing.getWorkspaceId());
        ChannelEntity updated = channelService.updateChannel(channel);
        channelManager.restartChannel(id);
        auditEventService.record("UPDATE", "CHANNEL", String.valueOf(id), updated.getName(), null);
        return R.ok(updated);
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "删除渠道")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity channel = channelService.getChannel(id);
        verifyResourceWorkspace(channel.getWorkspaceId(), workspaceId);
        channelManager.stopChannel(id);
        channelService.deleteChannel(id);
        auditEventService.record("DELETE", "CHANNEL", String.valueOf(id), channel.getName(), null);
        return R.ok();
    }

    @RequireWorkspaceRole("admin")
    @Operation(summary = "启用/禁用渠道")
    @PutMapping("/{id}/toggle")
    public R<ChannelEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled,
                                   @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        ChannelEntity existing = channelService.getChannel(id);
        verifyResourceWorkspace(existing.getWorkspaceId(), workspaceId);
        ChannelEntity channel = channelService.toggleChannel(id, enabled);
        // 联动 ChannelManager：启用时启动，禁用时停止
        if (enabled) {
            channelManager.startChannel(channel);
        } else {
            channelManager.stopChannel(id);
        }
        auditEventService.record(enabled ? "ENABLE" : "DISABLE", "CHANNEL", String.valueOf(id), channel.getName(), null);
        return R.ok(channel);
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "获取渠道运行状态")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        return R.ok(channelManager.getStatus());
    }

    private void verifyResourceWorkspace(Long resourceWorkspaceId, Long headerWorkspaceId) {
        long requestedWs = headerWorkspaceId != null ? headerWorkspaceId : 1L;
        if (resourceWorkspaceId != null && !resourceWorkspaceId.equals(requestedWs)) {
            throw new MateClawException("资源不属于当前工作区");
        }
    }
}
