package vip.mate.workflow.runtime;

import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelManager;

import java.util.Optional;

/**
 * Production binding for {@link ChannelDispatcher}. Looks the channel up by
 * type via {@link ChannelManager#getAdapterByType} and either calls
 * {@code proactiveSend} when the adapter supports it or {@code sendMessage}
 * otherwise. A missing adapter or one that's not running is reported back
 * as a failed dispatch — the step adapter decides whether that fails the
 * step or merely records a partial result.
 */
@Component
public class DefaultChannelDispatcher implements ChannelDispatcher {

    private final ChannelManager channelManager;

    public DefaultChannelDispatcher(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    @Override
    public DispatchResult dispatch(long workspaceId, String channelType, String targetId, String content) {
        if (channelType == null || channelType.isBlank()) {
            return DispatchResult.fail("channelType is required");
        }
        Optional<ChannelAdapter> adapterOpt = channelManager.getAdapterByType(channelType);
        if (adapterOpt.isEmpty()) {
            return DispatchResult.fail("no active adapter for channel type '" + channelType + "'");
        }
        ChannelAdapter adapter = adapterOpt.get();
        if (!adapter.isRunning()) {
            return DispatchResult.fail("channel '" + channelType + "' adapter is not running");
        }
        if (targetId == null || targetId.isBlank()) {
            return DispatchResult.fail("missing targetId for channel '" + channelType + "'");
        }
        try {
            if (adapter.supportsProactiveSend()) {
                adapter.proactiveSend(targetId, content);
            } else {
                adapter.sendMessage(targetId, content);
            }
            return DispatchResult.ok();
        } catch (Exception e) {
            return DispatchResult.fail("dispatch to '" + channelType + "' failed: " + e.getMessage());
        }
    }
}
