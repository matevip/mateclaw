package vip.mate.cron.delivery;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import vip.mate.channel.ChannelMessageRenderer;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.dashboard.model.CronJobRunEntity;
import vip.mate.dashboard.repository.CronJobRunMapper;

import java.util.List;

/**
 * RFC-063r §2.6.1: Template-Method base for {@link CronResultDelivery}.
 *
 * <p>Owns the cross-strategy invariants:
 * <ul>
 *   <li><b>Idempotency</b> — atomic SQL CAS on
 *       {@code mate_cron_job_run.delivery_status} via {@link #claimRun} so
 *       only one listener instance proceeds per run row even across cluster
 *       deployments (replaces RFC-063 v1's process-local Caffeine cache).</li>
 *   <li><b>State-machine bookkeeping</b> — {@link #markDelivered} on success,
 *       {@link #markNotDelivered} on failure (truncated message via Hutool
 *       {@code StrUtil.maxLength} per RFC §2.6.1).</li>
 *   <li><b>Render hook</b> — {@link #renderForChannel} delegates to the
 *       project's existing {@link ChannelMessageRenderer} so per-channel
 *       markdown / Block Kit rendering stays consistent with the inline reply
 *       path.</li>
 * </ul>
 *
 * <p>Concrete subclasses implement only
 * {@link #doDeliver(CronJobEntity, AssistantMessage, CronJobRunEntity)}.
 */
@Slf4j
public abstract class AbstractCronResultDelivery implements CronResultDelivery {

    /**
     * Default platform message-length cap used when the bound channel type
     * is unknown — matches Telegram's 4096 ceiling, the most permissive
     * among the small-cap platforms in {@link ChannelMessageRenderer#PLATFORM_LIMITS}.
     * RFC-063r §2.11 will refine this in PR-4 by passing per-channel limits
     * through {@code DeliveryOptions}.
     */
    private static final int DEFAULT_RENDER_MAX_LEN = 4096;

    private final CronJobRunMapper runMapper;

    protected AbstractCronResultDelivery(CronJobRunMapper runMapper) {
        this.runMapper = runMapper;
    }

    @Override
    public final DeliveryOutcome deliver(CronJobEntity job, AssistantMessage result, CronJobRunEntity run) {
        if (!claimRun(run)) {
            return DeliveryOutcome.skipped("already-claimed-by-other-instance");
        }
        try {
            DeliveryOutcome outcome = doDeliver(job, result, run);
            markDelivered(run, outcome);
            return outcome;
        } catch (Exception e) {
            markNotDelivered(run, e);
            throw e;
        }
    }

    /** Strategy's actual delivery work — invoked after CAS success. */
    protected abstract DeliveryOutcome doDeliver(
            CronJobEntity job, AssistantMessage result, CronJobRunEntity run);

    /**
     * RFC-063r §2.11: filter thinking + tool-call markers and join the
     * platform-truncated segments into a single string. Concrete strategies
     * call this before handing the result to the channel-specific send call.
     *
     * <p>Null-safe — null/empty {@link AssistantMessage} returns "" so
     * adapters never NPE.
     */
    protected String renderForChannel(AssistantMessage msg, Long channelId) {
        if (msg == null) return "";
        String text = msg.getText() != null ? msg.getText() : "";
        if (text.isEmpty()) return "";
        try {
            List<String> segments = ChannelMessageRenderer.renderForChannel(
                    text, /* filterThinking */ true, /* filterToolMessages */ true,
                    /* messageFormat */ null, DEFAULT_RENDER_MAX_LEN);
            return segments.isEmpty() ? "" : String.join("\n\n", segments);
        } catch (Exception e) {
            log.debug("[CronDelivery] renderForChannel failed (channelId={}); falling back to raw text: {}",
                    channelId, e.getMessage());
            return text;
        }
    }

    // ---------- SQL state-machine helpers ----------

    /**
     * Atomic SQL CAS: transition delivery_status from {@code NONE} or
     * {@code PENDING} → {@code PENDING}. Returns true iff this instance won
     * the race. NONE-eligibility lets fresh runs claim without a separate
     * "first-time" branch; PENDING-eligibility covers the rare same-instance
     * retry inside the listener (cluster paths can't normally hit this).
     */
    private boolean claimRun(CronJobRunEntity run) {
        return runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .in(CronJobRunEntity::getDeliveryStatus, "NONE", "PENDING", null)
                .set(CronJobRunEntity::getDeliveryStatus, "PENDING")) == 1;
    }

    private void markDelivered(CronJobRunEntity run, DeliveryOutcome o) {
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getDeliveryStatus, "DELIVERED")
                .set(CronJobRunEntity::getDeliveryTarget, o.target()));
    }

    private void markNotDelivered(CronJobRunEntity run, Exception e) {
        runMapper.update(null, new LambdaUpdateWrapper<CronJobRunEntity>()
                .eq(CronJobRunEntity::getId, run.getId())
                .set(CronJobRunEntity::getDeliveryStatus, "NOT_DELIVERED")
                .set(CronJobRunEntity::getDeliveryError, StrUtil.maxLength(e.getMessage(), 500)));
    }
}
