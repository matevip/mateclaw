package vip.mate.channel.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.common.result.R;

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

    @Operation(summary = "获取渠道列表")
    @GetMapping
    public R<List<ChannelEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (workspaceId != null) {
            return R.ok(channelService.listChannelsByWorkspace(workspaceId));
        }
        return R.ok(channelService.listChannels());
    }

    @Operation(summary = "按类型获取渠道列表")
    @GetMapping("/type/{channelType}")
    public R<List<ChannelEntity>> listByType(@PathVariable String channelType) {
        return R.ok(channelService.listChannelsByType(channelType));
    }

    @Operation(summary = "获取渠道详情")
    @GetMapping("/{id}")
    public R<ChannelEntity> get(@PathVariable Long id) {
        return R.ok(channelService.getChannel(id));
    }

    @Operation(summary = "创建渠道")
    @PostMapping
    public R<ChannelEntity> create(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody ChannelEntity channel) {
        if (workspaceId != null) {
            channel.setWorkspaceId(workspaceId);
        }
        return R.ok(channelService.createChannel(channel));
    }

    @Operation(summary = "更新渠道")
    @PutMapping("/{id}")
    public R<ChannelEntity> update(@PathVariable Long id, @RequestBody ChannelEntity channel) {
        channel.setId(id);
        ChannelEntity updated = channelService.updateChannel(channel);
        // 配置变更后热替换渠道（新 Adapter 就绪后才替换旧的，失败则保留旧的）
        channelManager.restartChannel(id);
        return R.ok(updated);
    }

    @Operation(summary = "删除渠道")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        // 先停止渠道再删除
        channelManager.stopChannel(id);
        channelService.deleteChannel(id);
        return R.ok();
    }

    @Operation(summary = "启用/禁用渠道")
    @PutMapping("/{id}/toggle")
    public R<ChannelEntity> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        ChannelEntity channel = channelService.toggleChannel(id, enabled);
        // 联动 ChannelManager：启用时启动，禁用时停止
        if (enabled) {
            channelManager.startChannel(channel);
        } else {
            channelManager.stopChannel(id);
        }
        return R.ok(channel);
    }

    @Operation(summary = "获取渠道运行状态")
    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        return R.ok(channelManager.getStatus());
    }
}
