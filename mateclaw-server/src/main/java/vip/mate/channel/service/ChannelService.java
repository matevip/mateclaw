package vip.mate.channel.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.exception.MateClawException;

import java.util.List;

/**
 * 渠道业务服务
 * <p>
 * 负责渠道的 CRUD 管理。
 * 渠道的运行时生命周期由 ChannelManager 管理。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelService {

    private final ChannelMapper channelMapper;

    /**
     * 获取所有渠道列表
     */
    public List<ChannelEntity> listChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .orderByDesc(ChannelEntity::getEnabled)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 按工作区列出渠道
     */
    public List<ChannelEntity> listChannelsByWorkspace(Long workspaceId) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getWorkspaceId, workspaceId)
                .orderByDesc(ChannelEntity::getEnabled)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 获取已启用的渠道列表（ChannelManager 启动时使用）
     */
    public List<ChannelEntity> listEnabledChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getEnabled, true)
                .orderByAsc(ChannelEntity::getChannelType));
    }

    /**
     * 按类型获取渠道列表
     */
    public List<ChannelEntity> listChannelsByType(String channelType) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getChannelType, channelType)
                .orderByDesc(ChannelEntity::getCreateTime));
    }

    /**
     * 获取渠道详情
     */
    public ChannelEntity getChannel(Long id) {
        ChannelEntity channel = channelMapper.selectById(id);
        if (channel == null) {
            throw new MateClawException("渠道不存在: " + id);
        }
        return channel;
    }

    /**
     * 创建渠道
     */
    public ChannelEntity createChannel(ChannelEntity channel) {
        // 验证名称
        if (channel.getName() == null || channel.getName().isBlank()) {
            throw new MateClawException("渠道名称不能为空");
        }
        if (channel.getChannelType() == null || channel.getChannelType().isBlank()) {
            throw new MateClawException("渠道类型不能为空");
        }
        if (channel.getEnabled() == null) {
            channel.setEnabled(false);
        }
        channelMapper.insert(channel);
        log.info("Created channel: {} (type={})", channel.getName(), channel.getChannelType());
        return channel;
    }

    /**
     * 更新渠道
     */
    public ChannelEntity updateChannel(ChannelEntity channel) {
        ChannelEntity existing = getChannel(channel.getId());
        channelMapper.updateById(channel);
        log.info("Updated channel: {}", existing.getName());
        return channel;
    }

    /**
     * 删除渠道
     */
    public void deleteChannel(Long id) {
        ChannelEntity channel = getChannel(id);
        channelMapper.deleteById(id);
        log.info("Deleted channel: {}", channel.getName());
    }

    /**
     * 启用/禁用渠道
     */
    public ChannelEntity toggleChannel(Long id, boolean enabled) {
        ChannelEntity channel = getChannel(id);
        channel.setEnabled(enabled);
        channelMapper.updateById(channel);
        log.info("Channel {} {}", channel.getName(), enabled ? "enabled" : "disabled");
        return channel;
    }
}
