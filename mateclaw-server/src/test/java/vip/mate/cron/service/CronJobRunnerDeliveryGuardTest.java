package vip.mate.cron.service;

import org.junit.jupiter.api.Test;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-063r §2.13 (Issue #25 — second symptom):
 * {@link CronJobRunner#wrapWithDeliveryGuard} must prepend a system note
 * for channel-bound cron runs and pass through web-origin runs unchanged.
 */
class CronJobRunnerDeliveryGuardTest {

    @Test
    void channelBoundCron_prependsDeliveryGuard() {
        ChatOrigin channelOrigin = new ChatOrigin(
                /* agentId */ 7L, "cron_7", "system", 1L, null,
                /* channelId */ 9L, new ChannelTarget("group-a", null, null));
        String input = "提醒我喝水并发到微信";
        String wrapped = CronJobRunner.wrapWithDeliveryGuard(input, channelOrigin);

        assertTrue(wrapped.contains("[系统说明]"),
                "Channel-bound cron must include system note (RFC-063r §2.13)");
        assertTrue(wrapped.contains("不要尝试调用 CLI"),
                "system note must explicitly forbid CLI hallucination");
        assertTrue(wrapped.endsWith(input),
                "user message must be appended after the system note");
    }

    @Test
    void webOriginCron_passesThroughUnchanged() {
        ChatOrigin webOrigin = ChatOrigin.web("cron_1", "system", 1L, null);
        String input = "Daily wiki update";
        assertEquals(input, CronJobRunner.wrapWithDeliveryGuard(input, webOrigin),
                "web-origin cron must keep pre-RFC behavior");
    }

    @Test
    void emptyOrigin_passesThroughUnchanged() {
        assertEquals("hello", CronJobRunner.wrapWithDeliveryGuard("hello", ChatOrigin.EMPTY));
        assertEquals("hello", CronJobRunner.wrapWithDeliveryGuard("hello", null));
    }
}
